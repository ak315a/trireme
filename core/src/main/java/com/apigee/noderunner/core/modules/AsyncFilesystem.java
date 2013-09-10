/**
 * Copyright (C) 2013 Apigee Corp. and other Noderunner contributors.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.apigee.noderunner.core.modules;

import com.apigee.noderunner.core.NodeRuntime;
import com.apigee.noderunner.core.internal.InternalNodeModule;
import com.apigee.noderunner.core.internal.NodeOSException;
import com.apigee.noderunner.core.internal.Utils;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.annotations.JSFunction;
import org.mozilla.javascript.annotations.JSGetter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.reflect.InvocationTargetException;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousFileChannel;
import java.nio.channels.CompletionHandler;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotLinkException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static com.apigee.noderunner.core.internal.ArgUtils.*;

/**
 * An implementation of the "fs" internal Node module. The "fs.js" script depends on it.
 * This is a Java-7-specific version of this module that uses the new NIO file APIs for async
 * file I/O and for a wider variety of compatibility with native Node.
 */
public class AsyncFilesystem
    implements InternalNodeModule
{
    private static final Logger log = LoggerFactory.getLogger(AsyncFilesystem.class);

    @Override
    public String getModuleName()
    {
        return "fs";
    }

    @Override
    public Scriptable registerExports(Context cx, Scriptable scope, NodeRuntime runner)
        throws InvocationTargetException, IllegalAccessException, InstantiationException
    {
        ScriptableObject.defineClass(scope, FSImpl.class, false, true);
        ScriptableObject.defineClass(scope, StatsImpl.class, false, true);

        FSImpl fs = (FSImpl) cx.newObject(scope, FSImpl.CLASS_NAME);
        fs.initialize(runner, runner.getAsyncPool());
        ScriptableObject.defineClass(fs, StatsImpl.class, false, true);
        return fs;
    }

    public static class FSImpl
        extends AbstractFilesystem
    {
        public static final String CLASS_NAME = "_fsClass";
        private static final int FIRST_FD = 4;

        protected NodeRuntime runner;
        protected ExecutorService pool;
        private final AtomicInteger nextFd = new AtomicInteger(FIRST_FD);
        private final ConcurrentHashMap<Integer, FileHandle> descriptors =
            new ConcurrentHashMap<Integer, FileHandle>();

        @Override
        public String getClassName() {
            return CLASS_NAME;
        }

        protected void initialize(NodeRuntime runner, ExecutorService fsPool)
        {
            this.runner = runner;
            this.pool = fsPool;
        }

        @Override
        public void cleanup()
        {
            for (FileHandle handle : descriptors.values()) {
                if (log.isDebugEnabled()) {
                    log.debug("Closing leaked file descriptor " + handle);
                }
                if (handle.file != null) {
                    try {
                        handle.file.close();
                    } catch (IOException ignore) {
                    }
                }
            }
            descriptors.clear();
        }

        private static Object mapResponse(Object[] ret)
        {
            if ((ret == null) || (ret.length < 2)) {
                return null;
            }
            return ret[1];
        }

        /**
         * This is a generic wrapper that executes an action in a thread pool if there is a
         * callback function defined, or in the current thread if it is not.
         */
        private Object runAction(final Context cx, final Function callback, final AsyncAction action)
        {
            if (callback == null) {
                try {
                    Object[] ret = action.execute();
                    return mapResponse(ret);

                } catch (NodeOSException e) {
                    if (log.isDebugEnabled()) {
                        log.debug("I/O exception: {}: {}", e.getCode(), e);
                    }
                    Object[] err = action.mapSyncException(e);
                    if (err == null) {
                        throw Utils.makeError(cx, this, e);
                    }
                    return err[1];
                }
            }

            final FSImpl self = this;
            final Scriptable domain = runner.getDomain();
            runner.pin();
            pool.execute(new Runnable()
            {
                @Override
                public void run()
                {
                    if (log.isDebugEnabled()) {
                        log.debug("Executing async action {}", action);
                    }
                    try {
                        Object[] args = action.execute();
                        if (args == null) {
                            args = new Object[0];
                        }
                        runner.enqueueCallback(callback, callback, null, domain, args);
                    } catch (NodeOSException e) {
                        if (log.isDebugEnabled()) {
                            log.debug("Async action {} failed: {}: {}", action, e.getCode(), e);
                        }
                        runner.enqueueCallback(callback, callback, null, domain,
                                               action.mapException(cx, self, e));
                    } finally {
                        runner.unPin();
                    }
                }
            });
            return null;
        }

        private Path translatePath(String path)
            throws NodeOSException
        {
            File trans = runner.translatePath(path);
            if (trans == null) {
                throw new NodeOSException(Constants.ENOENT, path);
            }
            return Paths.get(trans.getPath());
        }

        private FileHandle ensureHandle(int fd)
            throws NodeOSException
        {
            FileHandle handle = descriptors.get(fd);
            if (handle == null) {
                throw new NodeOSException(Constants.EBADF);
            }
            return handle;
        }

        private FileHandle ensureRegularFileHandle(int fd)
            throws NodeOSException
        {
            FileHandle h = ensureHandle(fd);
            if (h.file == null) {
                if (Files.isDirectory(h.path)) {
                    throw new NodeOSException(Constants.EISDIR);
                }
                throw new NodeOSException(Constants.EBADF);
            }
            return h;
        }

        private static Buffer.BufferImpl ensureBuffer(Context cx, Scriptable scope,
                                                      Object[] args, int pos)
        {
            ensureArg(args, pos);
            try {
                return (Buffer.BufferImpl)args[pos];
            } catch (ClassCastException cce) {
                throw Utils.makeError(cx, scope,
                                      "Not a buffer", Constants.EINVAL);
            }
        }

        @JSFunction
        public static Object open(final Context cx, final Scriptable thisObj, Object[] args, Function func)
        {
            final String pathStr = stringArg(args, 0);
            final int flags = intArg(args, 1);
            final int mode = intArg(args, 2);
            Function callback = functionArg(args, 3, false);
            final FSImpl fs = (FSImpl)thisObj;

            return fs.runAction(cx, callback, new AsyncAction()
            {
                @Override
                public Object[] execute() throws NodeOSException
                {
                    return fs.doOpen(pathStr, flags, mode);
                }

                @Override
                public Object[] mapException(Context cx, Scriptable scope, NodeOSException e)
                {
                    return new Object[] { Utils.makeErrorObject(cx, thisObj, e) };
                }
            });
        }

        private Object[] doOpen(String pathStr, int flags, int mode)
            throws NodeOSException
        {
            if (log.isDebugEnabled()) {
                log.debug("open({}, {}, {})", pathStr, flags, mode);
            }

            Path path = translatePath(pathStr);
            AsynchronousFileChannel file = null;

            // To support "lchmod", we need to check "O_SYMLINK" here too
            if (!Files.isDirectory(path)) {
                // Open an AsynchronousFileChannel using all the relevant open options.
                // But if we are opening a symbolic link or directory, just record the path and go on
                HashSet<OpenOption> options = new HashSet<OpenOption>();
                if ((flags & Constants.O_CREAT) != 0) {
                    if ((flags & Constants.O_EXCL) != 0) {
                        options.add(StandardOpenOption.CREATE_NEW);
                    } else {
                        options.add(StandardOpenOption.CREATE);
                    }
                }
                if ((flags & Constants.O_RDWR) != 0) {
                    options.add(StandardOpenOption.READ);
                    options.add(StandardOpenOption.WRITE);
                } else if ((flags & Constants.O_WRONLY) != 0) {
                    options.add(StandardOpenOption.WRITE);
                } else {
                    options.add(StandardOpenOption.READ);
                }

                if ((flags & Constants.O_TRUNC) != 0) {
                    options.add(StandardOpenOption.TRUNCATE_EXISTING);
                }
                if ((flags & Constants.O_SYNC) != 0) {
                    options.add(StandardOpenOption.SYNC);
                }

                try {
                    if (log.isDebugEnabled()) {
                        log.debug("Opening {} with {}", path, options);
                    }
                    file = AsynchronousFileChannel.open(path, options, pool,
                                                        PosixFilePermissions.asFileAttribute(modeToPerms(mode)));

                } catch (NoSuchFileException fnfe) {
                    log.debug("File not found");
                    throw new NodeOSException(Constants.ENOENT, pathStr);
                } catch (IOException ioe) {
                    if (log.isDebugEnabled()) {
                        log.debug("I/O error: {}", ioe);
                    }
                    throw new NodeOSException(Constants.EIO, ioe, pathStr);
                }
            }

            try {
                FileHandle fileHandle = new FileHandle(path, file);
                // Replace this if we choose to support "lchmod"
                /*
                if ((flags & Constants.O_SYMLINK) != 0) {
                    fileHandle.noFollow = true;
                }
                */
                int fd = nextFd.getAndIncrement();
                if (log.isDebugEnabled()) {
                    log.debug("  open({}) = {}", pathStr, fd);
                }
                if (((flags & Constants.O_APPEND) != 0) && (file != null) && (file.size() > 0)) {
                    if (log.isDebugEnabled()) {
                        log.debug("  setting file position to {}", file.size());
                    }
                    fileHandle.position = file.size();
                }
                descriptors.put(fd, fileHandle);
                return new Object [] { Context.getUndefinedValue(), fd };
            } catch (IOException ioe) {
                if (log.isDebugEnabled()) {
                    log.debug("I/O error: {}", ioe);
                }
                throw new NodeOSException(Constants.EIO, ioe, pathStr);
            }
        }

        @JSFunction
        public static void close(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            final FSImpl fs = (FSImpl)thisObj;
            final int fd = intArg(args, 0);
            Function callback = functionArg(args, 1, false);

            fs.runAction(cx, callback, new AsyncAction()
            {
                @Override
                public Object[] execute()
                    throws NodeOSException
                {
                    fs.doClose(fd);
                    return null;
                }
            });
        }

        private void doClose(int fd)
            throws NodeOSException
        {
            FileHandle handle = ensureRegularFileHandle(fd);
            try {
                if (log.isDebugEnabled()) {
                    log.debug("close({})", fd);
                }
                if (handle.file != null) {
                    handle.file.close();
                }
                descriptors.remove(fd);
            } catch (IOException ioe) {
                throw new NodeOSException(Constants.EIO, ioe);
            }
        }

        @JSFunction
        public static Object read(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            FSImpl fs = (FSImpl)thisObj;
            int fd = intArg(args, 0);
            Buffer.BufferImpl buf = ensureBuffer(cx, thisObj, args, 1);
            int off = intArgOnly(cx, fs, args, 2, 0);
            int len = intArgOnly(cx, fs, args, 3, 0);
            long pos = longArgOnly(cx, fs, args, 4, -1L);
            Function callback = functionArg(args, 5, false);

            if (off >= buf.getLength()) {
                throw Utils.makeError(cx, thisObj, "Offset is out of bounds", Constants.EINVAL);
            }
            if ((off + len) > buf.getLength()) {
                throw Utils.makeError(cx, thisObj, "Length extends beyond buffer", Constants.EINVAL);
            }

            try {
                return mapResponse(fs.doRead(cx, fd, buf, off, len, pos, callback));

            } catch (NodeOSException ne) {
                Object err = Utils.makeErrorObject(cx, thisObj, ne);
                if (callback == null) {
                    return err;
                }
                fs.runner.enqueueCallback(callback, callback, null, fs.runner.getDomain(), new Object[] { err });
                return null;
            }
        }

        private Object[] doRead(final Context cx, int fd, final Buffer.BufferImpl buf,
                                final int off, final int len, long pos, final Function callback)
            throws NodeOSException
        {
            byte[] bytes = buf.getArray();
            int bytesOffset = buf.getArrayOffset() + off;
            ByteBuffer readBuf = ByteBuffer.wrap(bytes, bytesOffset, len);
            final FileHandle handle = ensureRegularFileHandle(fd);

            if (pos < 0L) {
                pos = handle.position;
            }

            if (callback == null) {
                int count;
                try {
                    Future<Integer> result = handle.file.read(readBuf, pos);
                    count = result.get();
                    handle.position += count;

                } catch (InterruptedException ie) {
                    throw new NodeOSException(Constants.EINTR);
                } catch (ExecutionException e) {
                    throw new NodeOSException(Constants.EIO, e.getCause());
                }
                // Node (like C) expects 0 on EOF, not -1
                if (count < 0) {
                    count = 0;
                }
                if (log.isDebugEnabled()) {
                    log.debug("read({}, {}, {}) = {}",
                              off, len, pos, count);
                }
                return new Object[] { Context.getUndefinedValue(), count, buf };

            } else {
                final Scriptable domain = runner.getDomain();
                final long readPos = pos;
                runner.pin();
                handle.file.read(readBuf, pos, null,
                                 new CompletionHandler<Integer, Object>()
                                 {
                                     @Override
                                     public void completed(Integer result, Object attachment)
                                     {
                                         int count = result;
                                         if (count < 0) {
                                             count = 0;
                                         }
                                         if (log.isDebugEnabled()) {
                                             log.debug("async read({}, {}, {}) = {}",
                                                       off, len, readPos, count);
                                         }
                                         handle.position += count;

                                         runner.enqueueCallback(callback, callback, null, domain,
                                                                new Object[] { Context.getUndefinedValue(), count, buf });
                                         runner.unPin();
                                     }

                                     @Override
                                     public void failed(Throwable t, Object attachment)
                                     {
                                         runner.enqueueCallback(callback, callback, null, domain,
                                                                new Object[] { Utils.makeErrorObject(
                                                                                 cx, FSImpl.this, Constants.EIO, Constants.EIO),
                                                                               0, buf });
                                         runner.unPin();
                                     }
                                 });
                return null;
            }
        }

        @JSFunction
        public static Object write(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            FSImpl fs = (FSImpl)thisObj;
            int fd = intArg(args, 0);
            Buffer.BufferImpl buf = ensureBuffer(cx, thisObj, args, 1);
            int off = intArgOnly(cx, fs, args, 2, 0);
            int len = intArgOnly(cx, fs, args, 3, 0);
            long pos = longArgOnly(cx, fs, args, 4, 0L);
            Function callback = functionArg(args, 5, false);

            if (off >= buf.getLength()) {
                throw Utils.makeError(cx, thisObj, "Offset is out of bounds", "EINVAL");
            }
            if ((off + len) > buf.getLength()) {
                throw Utils.makeError(cx, thisObj, "Length extends beyond buffer", "EINVAL");
            }

            try {
                return mapResponse(fs.doWrite(cx, fd, buf, off, len, pos, callback));

            } catch (NodeOSException ne) {
                Object err = Utils.makeErrorObject(cx, thisObj, ne);
                if (callback == null) {
                    return err;
                }
                fs.runner.enqueueCallback(callback, callback, null, fs.runner.getDomain(), new Object[] { err });
                return null;
            }
        }

        private Object[] doWrite(final Context cx, int fd, final Buffer.BufferImpl buf,
                                 final int off, final int len, long pos, final Function callback)
            throws NodeOSException
        {
            byte[] bytes = buf.getArray();
            int bytesOffset = buf.getArrayOffset() + off;
            ByteBuffer writeBuf = ByteBuffer.wrap(bytes, bytesOffset, len);
            final FileHandle handle = ensureRegularFileHandle(fd);

            if (pos <= 0L) {
                pos = handle.position;
            }

            if (callback == null) {
                int count;
                try {
                    Future<Integer> result = handle.file.write(writeBuf, pos);
                    count = result.get();
                    handle.position += count;
                    if (log.isDebugEnabled()) {
                        log.debug("write({}, {}, {}) = {}",
                                  off, len, pos, count);
                    }

                } catch (InterruptedException e) {
                    throw new NodeOSException(Constants.EINTR);
                } catch (ExecutionException e) {
                    throw new NodeOSException(Constants.EIO, e.getCause());
                }
                return new Object[] { Context.getUndefinedValue(), count, buf };

            } else {
                final Scriptable domain = runner.getDomain();
                final long readPos = pos;

                // To make certain tests pass, we'll pre-increment the file position before writing
                // This doesn't make it a whole lot safer to issue a lot of async writes though
                handle.position += writeBuf.remaining();

                runner.pin();
                handle.file.write(writeBuf, pos, 0,
                                  new CompletionHandler<Integer, Integer>()
                                  {
                                      @Override
                                      public void completed(Integer result, Integer attachment)
                                      {
                                          int count = result;
                                          if (log.isDebugEnabled()) {
                                              log.debug("write({}, {}, {}) = {}",
                                                        off, len, readPos, count);
                                          }

                                          runner.enqueueCallback(callback, callback, null, domain,
                                                                 new Object[] { Context.getUndefinedValue(), count, buf });
                                          runner.unPin();
                                      }

                                      @Override
                                      public void failed(Throwable exc, Integer attachment)
                                      {
                                          runner.enqueueCallback(callback, callback, null, domain,
                                                                new Object[] { Utils.makeErrorObject(
                                                                                 cx, FSImpl.this, Constants.EIO, Constants.EIO),
                                                                               0, buf });
                                          runner.unPin();
                                      }
                                  });
                return null;
            }
        }

        @JSFunction
        public static void fsync(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            final FSImpl fs = (FSImpl)thisObj;
            final int fd = intArg(args, 0);
            Function callback = functionArg(args, 1, false);

            fs.runAction(cx, callback, new AsyncAction()
            {
                @Override
                public Object[] execute() throws NodeOSException
                {
                    fs.doSync(fd, true);
                    return null;
                }
            });
        }

        @JSFunction
        public static void fdatasync(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            final FSImpl fs = (FSImpl)thisObj;
            final int fd = intArg(args, 0);
            Function callback = functionArg(args, 1, false);

            fs.runAction(cx, callback, new AsyncAction()
            {
                @Override
                public Object[] execute() throws NodeOSException
                {
                    fs.doSync(fd, false);
                    return null;
                }
            });
        }

        private void doSync(int fd, boolean metaData)
            throws NodeOSException
        {
            FileHandle handle = ensureRegularFileHandle(fd);
            if (log.isDebugEnabled()) {
                log.debug("fsync({})", fd);
            }
            try {
                handle.file.force(metaData);
            } catch (IOException ioe) {
                throw new NodeOSException(Constants.EIO, ioe);
            }
        }

        @JSFunction
        public static void rename(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            final String oldPath = stringArg(args, 0);
            final String newPath = stringArg(args, 1);
            Function callback = functionArg(args, 2, false);
            final FSImpl fs = (FSImpl)thisObj;

            fs.runAction(cx, callback, new AsyncAction()
            {
                @Override
                public Object[] execute() throws NodeOSException
                {
                    fs.doRename(oldPath, newPath);
                    return null;
                }
            });
        }

        private void doRename(String oldPath, String newPath)
            throws NodeOSException
        {
            Path oldFile = translatePath(oldPath);
            Path newFile = translatePath(newPath);

            try {
                Files.copy(oldFile, newFile, StandardCopyOption.REPLACE_EXISTING);

            } catch (IOException ioe) {
                throw new NodeOSException(Constants.EIO, ioe, oldPath);
            }
        }

        @JSFunction
        public static void ftruncate(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            final FSImpl fs = (FSImpl)thisObj;
            final int fd = intArg(args, 0);
            final long len = longArgOnly(cx, fs, args, 1, 0);
            Function callback = functionArg(args, 2, false);

            fs.runAction(cx, callback, new AsyncAction()
            {
                @Override
                public Object[] execute()
                    throws NodeOSException
                {
                    fs.doTruncate(fd, len);
                    return null;
                }
            });
        }

        private void doTruncate(int fd, long len)
            throws NodeOSException
        {
            if (log.isDebugEnabled()) {
                log.debug("ftruncate({}, {})", fd, len);
            }
            try {
                FileHandle handle = ensureRegularFileHandle(fd);
                if (len > handle.file.size()) {
                    // AsynchronousFileChannel doesn't actually extend the file size, so do it a different way
                    RandomAccessFile tmp = new RandomAccessFile(handle.path.toFile(), "rw");
                    try {
                        tmp.setLength(len);
                    } finally {
                        tmp.close();
                    }
                } else {
                    handle.file.truncate(len);
                }
            } catch (IOException e) {
                throw new NodeOSException(Constants.EIO, e);
            }
        }

        @JSFunction
        public static void rmdir(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            final FSImpl fs = (FSImpl)thisObj;
            final String path = stringArg(args, 0);
            Function callback = functionArg(args, 1, false);

            fs.runAction(cx, callback, new AsyncAction()
            {
                @Override
                public Object[] execute() throws NodeOSException
                {
                    fs.doRmdir(path);
                    return null;
                }
            });
        }

        private void doRmdir(String path)
            throws NodeOSException
        {
            if (log.isDebugEnabled()) {
                log.debug("rmdir({})", path);
            }
            Path p = translatePath(path);
            if (!Files.isDirectory(p)) {
                throw new NodeOSException(Constants.ENOTDIR, path);
            }

            try {
                Files.delete(p);
            } catch (NoSuchFileException nfe) {
                throw new NodeOSException(Constants.ENOENT, nfe, path);
            } catch (IOException ioe) {
                throw new NodeOSException(Constants.EIO, ioe, path);
            }
        }

        @JSFunction
        public static void unlink(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            final String path = stringArg(args, 0);
            Function callback = functionArg(args, 1, false);
            final FSImpl fs = (FSImpl)thisObj;

            fs.runAction(cx, callback, new AsyncAction()
            {
                @Override
                public Object[] execute() throws NodeOSException
                {
                    fs.doUnlink(path);
                    return null;
                }
            });
        }

        private void doUnlink(String path)
            throws NodeOSException
        {
            if (log.isDebugEnabled()) {
                log.debug("unlink({})", path);
            }
            Path p = translatePath(path);

            try {
                Files.delete(p);

            } catch (NoSuchFileException nfe) {
                throw new NodeOSException(Constants.ENOENT, nfe, path);
            } catch (DirectoryNotEmptyException dne) {
                throw new NodeOSException(Constants.EINVAL, dne, path);
            } catch (IOException ioe) {
                throw new NodeOSException(Constants.EIO, ioe, path);
            }
        }

        @JSFunction
        public static void mkdir(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            final String path = stringArg(args, 0);
            final int mode = intArg(args, 1);
            Function callback = functionArg(args, 2, false);
            final FSImpl fs = (FSImpl)thisObj;

            fs.runAction(cx, callback, new AsyncAction()
            {
                @Override
                public Object[] execute()
                    throws NodeOSException
                {
                    fs.doMkdir(path, mode);
                    return null;
                }
            });
        }

        private void doMkdir(String path, int mode)
            throws NodeOSException
        {
            if (log.isDebugEnabled()) {
                log.debug("mkdir({})", path);
            }
            Path p  = translatePath(path);
            Set<PosixFilePermission> perms = modeToPerms(mode);

            try {
                Files.createDirectory(p,
                                      PosixFilePermissions.asFileAttribute(perms));

            } catch (FileAlreadyExistsException fee) {
                throw new NodeOSException(Constants.EEXIST, fee, path);
            } catch (IOException ioe) {
                throw new NodeOSException(Constants.EIO, ioe, path);
            }
        }

        @JSFunction
        public static Object readdir(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            final String path = stringArg(args, 0);
            Function callback = functionArg(args, 1, false);
            final FSImpl fs = (FSImpl)thisObj;

            return fs.runAction(cx, callback, new AsyncAction()
            {
                @Override
                public Object[] execute()
                    throws NodeOSException
                {
                    return fs.doReaddir(path);
                }
            });
        }

        private Object[] doReaddir(String dn)
            throws NodeOSException
        {
            Path sp = translatePath(dn);
            Context cx = Context.enter();
            if (!Files.isDirectory(sp)) {
                throw new NodeOSException(Constants.ENOTDIR, sp.toString());
            }
            try {
                final ArrayList<String> paths = new ArrayList<String>();
                Set<FileVisitOption> options = Collections.emptySet();
                Files.walkFileTree(sp, options, 1,
                                   new SimpleFileVisitor<Path>() {
                                       @Override
                                       public FileVisitResult visitFile(Path child, BasicFileAttributes attrs)
                                       {
                                           paths.add(child.getFileName().toString());
                                           return FileVisitResult.CONTINUE;
                                       }
                                   });


                Object[] objs = new Object[paths.size()];
                paths.toArray(objs);
                Scriptable fileList = cx.newArray(this, objs);
                if (log.isDebugEnabled()) {
                    log.debug("readdir({}) = {}", dn, objs.length);
                }
                return new Object[] { Context.getUndefinedValue(), fileList };

            } catch (IOException ioe) {
                throw new NodeOSException(Constants.EIO, ioe, dn);
            } finally {
                Context.exit();
            }
        }

        @JSFunction
        public static Object stat(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            final String path = stringArg(args, 0);
            Function callback = functionArg(args, 1, false);
            final FSImpl fs = (FSImpl)thisObj;

            return fs.runAction(cx, callback, new AsyncAction()
            {
                @Override
                public Object[] execute()
                    throws NodeOSException
                {
                    Path p = fs.translatePath(path);
                    return fs.doStat(p, false);
                }
            });
        }

        private Object[] doStat(Path p, boolean noFollow)
        {
            Context cx = Context.enter();
            try {
                PosixFileAttributes attrs;
                try {
                if (noFollow) {
                    attrs = Files.readAttributes(p, PosixFileAttributes.class,
                                                 LinkOption.NOFOLLOW_LINKS);
                } else {
                    attrs = Files.readAttributes(p, PosixFileAttributes.class);
                }
                } catch (NoSuchFileException nfe) {
                    throw new NodeOSException(Constants.ENOENT, nfe, p.toString());
                } catch (IOException ioe) {
                    throw new NodeOSException(Constants.EIO, ioe, p.toString());
                }

                StatsImpl s = (StatsImpl)cx.newObject(this, StatsImpl.CLASS_NAME);
                s.setAttributes(attrs);
                if (log.isTraceEnabled()) {
                    log.trace("stat {} = {}", p, s);
                }
                return new Object[] { Context.getUndefinedValue(), s };
            } finally {
                Context.exit();
            }
        }

        @JSFunction
        public static Object lstat(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            final String path = stringArg(args, 0);
            Function callback = functionArg(args, 1, false);
            final FSImpl fs = (FSImpl)thisObj;

            return fs.runAction(cx, callback, new AsyncAction()
            {
                @Override
                public Object[] execute()
                    throws NodeOSException
                {
                    Path p = fs.translatePath(path);
                    return fs.doStat(p, true);
                }
            });
        }

        @JSFunction
        public static Object fstat(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            final FSImpl fs = (FSImpl)thisObj;
            final int fd = intArg(args, 0);
            Function callback = functionArg(args, 1, false);

            return fs.runAction(cx, callback, new AsyncAction()
            {
                @Override
                public Object[] execute()
                    throws NodeOSException
                {
                    FileHandle fh = fs.ensureHandle(fd);
                    return fs.doStat(fh.path, fh.noFollow);
                }
            });
        }

        @JSFunction
        public static void utimes(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            final String path = stringArg(args, 0);
            final double atime = doubleArg(args, 1);
            final double mtime = doubleArg(args, 2);
            Function callback = functionArg(args, 3, false);
            final FSImpl self = (FSImpl)thisObj;

            self.runAction(cx, callback, new AsyncAction() {
                @Override
                public Object[] execute()
                    throws NodeOSException
                {
                    Path p = self.translatePath(path);
                    return self.doUTimes(p, atime, mtime, false);
                }
            });
        }

        @JSFunction
        public static void futimes(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            final int fd = intArg(args, 0);
            final double atime = doubleArg(args, 1);
            final double mtime = doubleArg(args, 2);
            Function callback = functionArg(args, 3, false);
            final FSImpl self = (FSImpl)thisObj;

            self.runAction(cx, callback, new AsyncAction() {
                @Override
                public Object[] execute()
                    throws NodeOSException
                {
                    FileHandle fh = self.ensureHandle(fd);
                    return self.doUTimes(fh.path, atime, mtime, fh.noFollow);
                }
            });
        }

        private Object[] doUTimes(Path path, double atime, double mtime, boolean nofollow)
            throws NodeOSException
        {
            try {
                BasicFileAttributeView attrView;
                if (nofollow) {
                    attrView = Files.getFileAttributeView(path, BasicFileAttributeView.class,
                                                          LinkOption.NOFOLLOW_LINKS);
                } else {
                    attrView = Files.getFileAttributeView(path, BasicFileAttributeView.class);
                }

                BasicFileAttributes attrs = attrView.readAttributes();
                // The timestamp seems to come from JavaScript as a decimal value of seconds
                FileTime newATime = FileTime.fromMillis((long)(atime * 1000.0));
                FileTime newMTime = FileTime.fromMillis((long)(mtime * 1000.0));
                attrView.setTimes(newMTime, newATime, attrs.creationTime());
            } catch (NoSuchFileException nfe) {
                throw new NodeOSException(Constants.ENOENT, nfe, path.toString());
            } catch (IOException ioe) {
                throw new NodeOSException(Constants.EIO, ioe, path.toString());
            }
            return new Object[] { Context.getUndefinedValue(), Context.getUndefinedValue() };
        }

        @JSFunction
        public static void chmod(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            final String path = stringArg(args, 0);
            final int mode = intArg(args, 1);
            Function callback = functionArg(args, 2, false);
            final FSImpl self = (FSImpl)thisObj;

            self.runAction(cx, callback, new AsyncAction() {
                @Override
                public Object[] execute() throws NodeOSException
                {
                    Path p = self.translatePath(path);
                    return self.doChmod(p, mode, false);
                }
            });
        }

        private static Set<PosixFilePermission> modeToPerms(int mode)
        {
            Set<PosixFilePermission> perms =
                EnumSet.noneOf(PosixFilePermission.class);
            if ((mode & Constants.S_IXUSR) != 0) {
                perms.add(PosixFilePermission.OWNER_EXECUTE);
            }
            if ((mode & Constants.S_IRUSR) != 0) {
                perms.add(PosixFilePermission.OWNER_READ);
            }
            if ((mode & Constants.S_IWUSR) != 0) {
                perms.add(PosixFilePermission.OWNER_WRITE);
            }
            if ((mode & Constants.S_IXGRP) != 0) {
                perms.add(PosixFilePermission.GROUP_EXECUTE);
            }
            if ((mode & Constants.S_IRGRP) != 0) {
                perms.add(PosixFilePermission.GROUP_READ);
            }
            if ((mode & Constants.S_IWGRP) != 0) {
                perms.add(PosixFilePermission.GROUP_WRITE);
            }
            if ((mode & Constants.S_IXOTH) != 0) {
                perms.add(PosixFilePermission.OTHERS_EXECUTE);
            }
            if ((mode & Constants.S_IROTH) != 0) {
                perms.add(PosixFilePermission.OTHERS_READ);
            }
            if ((mode & Constants.S_IWOTH) != 0) {
                perms.add(PosixFilePermission.OTHERS_WRITE);
            }
            return perms;
        }

        private Object[] doChmod(Path path, int mode, boolean noFollow)
            throws NodeOSException
        {
            Set<PosixFilePermission> perms = modeToPerms(mode);

            if (log.isDebugEnabled()) {
                log.debug("chmod({}, {}) to {}", path, mode, perms);
            }

            try {
                if (noFollow) {
                    Files.setAttribute(path, "posix:permissions", perms, LinkOption.NOFOLLOW_LINKS);
                } else {
                    Files.setAttribute(path, "posix:permissions", perms);
                }
                return new Object[] { Context.getUndefinedValue(), Context.getUndefinedValue() };
            } catch (NoSuchFileException nfe) {
                throw new NodeOSException(Constants.ENOENT, nfe, path.toString());
            } catch (IOException ioe) {
                throw new NodeOSException(Constants.EIO, ioe, path.toString());
            }
        }

        @JSFunction
        public static void fchmod(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            final int fd = intArg(args, 0);
            final int mode = intArg(args, 1);
            Function callback = functionArg(args, 2, false);
            final FSImpl self = (FSImpl)thisObj;

            self.runAction(cx, callback, new AsyncAction() {
                @Override
                public Object[] execute() throws NodeOSException
                {
                    FileHandle fh = self.ensureHandle(fd);
                    return self.doChmod(fh.path, mode, fh.noFollow);
                }
            });
        }

        @JSFunction
        public static void chown(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            throw Utils.makeError(cx, thisObj, "Not implemented");
        }

        @JSFunction
        public static void fchown(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            throw Utils.makeError(cx, thisObj, "Not implemented");
        }

        @JSFunction
        public static Object link(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            final String srcPath = stringArg(args, 0);
            final String destPath = stringArg(args, 1);
            Function callback = functionArg(args, 2, false);
            final FSImpl self = (FSImpl)thisObj;

            return self.runAction(cx, callback, new AsyncAction() {
                @Override
                public Object[] execute()
                   throws NodeOSException
                {
                    return self.doLink(destPath, srcPath);
                }
            });
        }

        private Object[] doLink(String destPath, String srcPath)
            throws NodeOSException
        {
            Path dest = translatePath(destPath);
            Path src = translatePath(srcPath);

            try {
                if (log.isDebugEnabled()) {
                    log.debug("link from {} to {}",
                              src, dest);
                }
                Files.createLink(dest, src);
                return new Object[] { Context.getUndefinedValue(), Context.getUndefinedValue() };

            } catch (FileAlreadyExistsException fae) {
                log.debug("FileAlreadyExists");
                throw new NodeOSException(Constants.EEXIST, fae, destPath);
            } catch (NoSuchFileException nfe) {
                log.debug("NoSuchFile");
                throw new NodeOSException(Constants.ENOENT, nfe, srcPath);
            } catch (IOException ioe) {
                log.debug("IOException: {}", ioe);
                throw new NodeOSException(Constants.EIO, ioe, destPath);
            }
        }

        @JSFunction
        public static Object symlink(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            final String srcPath = stringArg(args, 0);
            final String destPath = stringArg(args, 1);
            final String type = stringArg(args, 2, null);
            Function callback = functionArg(args, 3, false);
            final FSImpl self = (FSImpl)thisObj;

            return self.runAction(cx, callback, new AsyncAction() {
                @Override
                public Object[] execute()
                   throws NodeOSException
                {
                    return self.doSymlink(destPath, srcPath, type);
                }
            });
        }

        private Object[] doSymlink(String destPath, String srcPath, String type)
            throws NodeOSException
        {
            Path dest = translatePath(destPath);
            Path src = translatePath(srcPath);

            try {
                if (log.isDebugEnabled()) {
                    log.debug("symlink from {} to {}",
                              src, dest);
                }
                // TODO do we care about type?
                Files.createSymbolicLink(dest, src);
                return new Object[] { Context.getUndefinedValue(), Context.getUndefinedValue() };

            } catch (FileAlreadyExistsException fae) {
                log.debug("FileAlreadyExists");
                throw new NodeOSException(Constants.EEXIST, fae, destPath);
            } catch (NoSuchFileException nfe) {
                log.debug("NoSuchFile");
                throw new NodeOSException(Constants.ENOENT, nfe, srcPath);
            } catch (IOException ioe) {
                log.debug("IOException: {}", ioe);
                throw new NodeOSException(Constants.EIO, ioe, destPath);
            }
        }

        @JSFunction
        public static Object readlink(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            final String path = stringArg(args, 0);
            Function callback = functionArg(args, 1, false);
            final FSImpl self = (FSImpl)thisObj;

             return self.runAction(cx, callback, new AsyncAction() {
               @Override
               public Object[] execute()
                   throws NodeOSException
               {
                   return self.doReadLink(path);
               }
           });
        }

        private Object[] doReadLink(String pathStr)
            throws NodeOSException
        {
            Path path = translatePath(pathStr);

            try {
                Path target = Files.readSymbolicLink(path);
                if (log.isDebugEnabled()) {
                    log.debug("readLink({}) = {}", path, target);
                }

                String result;
                if (Files.isDirectory(target)) {
                    // There is a test that expects this.
                    result = target.toString() + '/';
                } else {
                    result = target.toString();
                }
                return new Object[] { Context.getUndefinedValue(), result };
            } catch (NoSuchFileException nfe) {
                log.debug("NoSuchFile");
                throw new NodeOSException(Constants.ENOENT, nfe, pathStr);
            } catch (NotLinkException nle) {
                log.debug("NotLink");
                throw new NodeOSException(Constants.EINVAL, nle, pathStr);
            } catch (IOException ioe) {
                log.debug("IOException: {}", ioe);
                throw new NodeOSException(Constants.EIO, ioe, pathStr);
            }
        }
    }

    public static class StatsImpl
        extends ScriptableObject
    {
        public static final String CLASS_NAME = "Stats";

        private PosixFileAttributes attrs;

        @Override
        public String getClassName() {
            return CLASS_NAME;
        }

        public void setAttributes(PosixFileAttributes attrs)
        {
            this.attrs = attrs;
        }

        // Fake "dev" and "ino" based on whatever information we can get from the product
        @JSGetter("dev")
        public int getDev()
        {
            return 0;
        }

        @JSGetter("ino")
        public int getIno()
        {
            Object ino = attrs.fileKey();
            if (ino instanceof Number) {
                return ((Number)ino).intValue();
            } else {
                return ino.hashCode();
            }
        }

        @JSGetter("mode")
        public int getMode()
        {
            int mode = 0;

            // File mode flags -- these are used by the JS code to handle "isFile" and other methods
            if (attrs.isRegularFile()) {
                mode |= Constants.S_IFREG;
            }
            if (attrs.isDirectory()) {
                mode |= Constants.S_IFDIR;
            }
            if (attrs.isSymbolicLink()) {
                mode |= Constants.S_IFLNK;
            }

            // Posix file perms
            Set<PosixFilePermission> perms = attrs.permissions();
            if (perms.contains(PosixFilePermission.GROUP_EXECUTE)) {
                mode |= Constants.S_IXGRP;
            }
            if (perms.contains(PosixFilePermission.GROUP_READ)) {
                mode |= Constants.S_IRGRP;
            }
            if (perms.contains(PosixFilePermission.GROUP_WRITE)) {
                mode |= Constants.S_IWGRP;
            }
            if (perms.contains(PosixFilePermission.OTHERS_EXECUTE)) {
                mode |= Constants.S_IXOTH;
            }
            if (perms.contains(PosixFilePermission.OTHERS_READ)) {
                mode |= Constants.S_IROTH;
            }
            if (perms.contains(PosixFilePermission.OTHERS_WRITE)) {
                mode |= Constants.S_IWOTH;
            }
            if (perms.contains(PosixFilePermission.OWNER_EXECUTE)) {
                mode |= Constants.S_IXUSR;
            }
            if (perms.contains(PosixFilePermission.OWNER_READ)) {
                mode |= Constants.S_IRUSR;
            }
            if (perms.contains(PosixFilePermission.OWNER_WRITE)) {
                mode |= Constants.S_IWUSR;
            }
            return mode;
        }

        // TODO nlink
        // TODO uid
        // TODO gid
        // TODO rdev

        @JSGetter("size")
        public double getSize() {
            return attrs.size();
        }

        // TODO blksize
        // TODO blocks

        @JSGetter("atime")
        public Object getATime()
        {
            return makeDate(attrs.lastAccessTime().toMillis());
        }

        @JSGetter("mtime")
        public Object getMTime()
        {
            return makeDate(attrs.lastModifiedTime().toMillis());
        }

        @JSGetter("ctime")
        public Object getCTime()
        {
            return makeDate(attrs.creationTime().toMillis());
        }

        @JSFunction
        public Object toJSON()
        {
            Scriptable s = Context.getCurrentContext().newObject(this);
            s.put("mode", s, getMode());
            s.put("size", s, getSize());
            s.put("mtime", s, getMTime());
            s.put("atime", s, getATime());
            s.put("ctime", s, getCTime());
            return s;
        }

        private Object makeDate(long ts)
        {
            return Context.getCurrentContext().newObject(this, "Date", new Object[] { ts });
        }
    }

    public static class FileHandle
    {
        static final String KEY = "_fileHandle";

        AsynchronousFileChannel file;
        Path path;
        long position;
        boolean noFollow;

        FileHandle(Path path, AsynchronousFileChannel file)
        {
            this.path = path;
            this.file = file;
        }
    }

    private abstract static class AsyncAction
    {
        public abstract Object[] execute()
            throws NodeOSException;

        public Object[] mapException(Context cx, Scriptable scope, NodeOSException e)
        {
            return new Object[] { Utils.makeErrorObject(cx, scope, e) };
        }

        public Object[] mapSyncException(NodeOSException e)
        {
            return null;
        }
    }
}
