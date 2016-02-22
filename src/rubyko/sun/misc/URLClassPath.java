/*
 * Copyright (c) 1997, 2013, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package rubyko.sun.misc;

import java.util.*;
import java.util.jar.JarFile;
import rubyko.sun.net.www.ParseUtil;
import java.net.JarURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.net.HttpURLConnection;
import java.net.URLStreamHandler;
import java.net.URLStreamHandlerFactory;
import java.io.*;
import java.security.Permission;

/**
 * This class is used to maintain a search path of URLs for loading classes
 * and resources from both JAR files and directories.
 *
 * @author  David Connelly
 */
public class URLClassPath {
    final static String USER_AGENT_JAVA_VERSION = "UA-Java-Version";
    final static String JAVA_VERSION;
    private static final boolean DEBUG;
    private static final boolean DISABLE_JAR_CHECKING;

    static {
        JAVA_VERSION = java.security.AccessController.doPrivileged(
            new rubyko.sun.security.action.GetPropertyAction("java.version"));
        DEBUG        = (java.security.AccessController.doPrivileged(
            new rubyko.sun.security.action.GetPropertyAction("sun.misc.URLClassPath.debug")) != null);
        String p = java.security.AccessController.doPrivileged(
            new rubyko.sun.security.action.GetPropertyAction("sun.misc.URLClassPath.disableJarChecking"));
        DISABLE_JAR_CHECKING = p != null ? p.equals("true") || p.equals("") : false;
    }

    /* The original search path of URLs. */
    private ArrayList<URL> path = new ArrayList<URL>();

    /* The stack of unopened URLs */
    Stack<URL> urls = new Stack<URL>();

    /* The resulting search path of Loaders */
    ArrayList<Loader> loaders = new ArrayList<Loader>();

    /* Map of each URL opened to its corresponding Loader */
    HashMap<String, Loader> lmap = new HashMap<String, Loader>();

    /* The jar protocol handler to use when creating new URLs */
    private URLStreamHandler jarHandler;

    /* Whether this URLClassLoader has been closed yet */
    private boolean closed = false;

    /**
     * Creates a new URLClassPath for the given URLs. The URLs will be
     * searched in the order specified for classes and resources. A URL
     * ending with a '/' is assumed to refer to a directory. Otherwise,
     * the URL is assumed to refer to a JAR file.
     *
     * @param urls the directory and JAR file URLs to search for classes
     *        and resources
     * @param factory the URLStreamHandlerFactory to use when creating new URLs
     */
    public URLClassPath(URL[] urls, URLStreamHandlerFactory factory) {
        for (int i = 0; i < urls.length; i++) {
            path.add(urls[i]);
        }
        push(urls);
        if (factory != null) {
            jarHandler = factory.createURLStreamHandler("jar");
        }
    }

    public URLClassPath(URL[] urls) {
        this(urls, null);
    }

    public synchronized List<IOException> closeLoaders() {
        if (closed) {
            return Collections.emptyList();
        }
        List<IOException> result = new LinkedList<IOException>();
        for (Loader loader : loaders) {
            try {
                loader.close();
            } catch (IOException e) {
                result.add (e);
            }
        }
        closed = true;
        return result;
    }

    /**
     * Appends the specified URL to the search path of directory and JAR
     * file URLs from which to load classes and resources.
     * <p>
     * If the URL specified is null or is already in the list of
     * URLs, then invoking this method has no effect.
     */
    public synchronized void addURL(URL url) {
        if (closed)
            return;
        synchronized (urls) {
            if (url == null || path.contains(url))
                return;

            urls.add(0, url);
            path.add(url);
        }
    }

    /**
     * Returns the original search path of URLs.
     */
    public URL[] getURLs() {
        synchronized (urls) {
            return path.toArray(new URL[path.size()]);
        }
    }


  
   

    

    /*
     * Pushes the specified URLs onto the list of unopened URLs.
     */
    private void push(URL[] us) {
        synchronized (urls) {
            for (int i = us.length - 1; i >= 0; --i) {
                urls.push(us[i]);
            }
        }
    }

    /**
     * Convert class path specification into an array of file URLs.
     *
     * The path of the file is encoded before conversion into URL
     * form so that reserved characters can safely appear in the path.
     */
    public static URL[] pathToURLs(String path) {
        StringTokenizer st = new StringTokenizer(path, File.pathSeparator);
        URL[] urls = new URL[st.countTokens()];
        int count = 0;
        while (st.hasMoreTokens()) {
            File f = new File(st.nextToken());
            try {
                f = new File(f.getCanonicalPath());
            } catch (IOException x) {
                // use the non-canonicalized filename
            }
            try {
                urls[count++] = ParseUtil.fileToEncodedURL(f);
            } catch (IOException x) { }
        }

        if (urls.length != count) {
            URL[] tmp = new URL[count];
            System.arraycopy(urls, 0, tmp, 0, count);
            urls = tmp;
        }
        return urls;
    }

    /*
     * Check whether the resource URL should be returned.
     * Return null on security check failure.
     * Called by java.net.URLClassLoader.
     */
    public URL checkURL(URL url) {
        try {
            check(url);
        } catch (Exception e) {
            return null;
        }

        return url;
    }

    /*
     * Check whether the resource URL should be returned.
     * Throw exception on failure.
     * Called internally within this file.
     */
    static void check(URL url) throws IOException {
        SecurityManager security = System.getSecurityManager();
        if (security != null) {
            URLConnection urlConnection = url.openConnection();
            Permission perm = urlConnection.getPermission();
            if (perm != null) {
                try {
                    security.checkPermission(perm);
                } catch (SecurityException se) {
                    // fallback to checkRead/checkConnect for pre 1.2
                    // security managers
                    if ((perm instanceof java.io.FilePermission) &&
                        perm.getActions().indexOf("read") != -1) {
                        security.checkRead(perm.getName());
                    } else if ((perm instanceof
                        java.net.SocketPermission) &&
                        perm.getActions().indexOf("connect") != -1) {
                        URL locUrl = url;
                        if (urlConnection instanceof JarURLConnection) {
                            locUrl = ((JarURLConnection)urlConnection).getJarFileURL();
                        }
                        security.checkConnect(locUrl.getHost(),
                                              locUrl.getPort());
                    } else {
                        throw se;
                    }
                }
            }
        }
    }

    /**
     * Inner class used to represent a loader of resources and classes
     * from a base URL.
     */
    private static class Loader implements Closeable {
        private final URL base;
        private JarFile jarfile; // if this points to a jar file

        /*
         * Creates a new Loader for the specified URL.
         */
        Loader(URL url) {
            base = url;
        }

        /*
         * Returns the base URL for this Loader.
         */
        URL getBaseURL() {
            return base;
        }

        URL findResource(final String name, boolean check) {
            URL url;
            try {
                url = new URL(base, ParseUtil.encodePath(name, false));
            } catch (MalformedURLException e) {
                throw new IllegalArgumentException("name");
            }

            try {
                if (check) {
                    URLClassPath.check(url);
                }

                /*
                 * For a HTTP connection we use the HEAD method to
                 * check if the resource exists.
                 */
                URLConnection uc = url.openConnection();
                if (uc instanceof HttpURLConnection) {
                    HttpURLConnection hconn = (HttpURLConnection)uc;
                    hconn.setRequestMethod("HEAD");
                    if (hconn.getResponseCode() >= HttpURLConnection.HTTP_BAD_REQUEST) {
                        return null;
                    }
                } else {
                    // our best guess for the other cases
                    uc.setUseCaches(false);
                    InputStream is = uc.getInputStream();
                    is.close();
                }
                return url;
            } catch (Exception e) {
                return null;
            }
        }

       
        /*
         * close this loader and release all resources
         * method overridden in sub-classes
         */
        public void close () throws IOException {
            if (jarfile != null) {
                jarfile.close();
            }
        }
    }
}
   
    

