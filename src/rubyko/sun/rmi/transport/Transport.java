/*
 * Copyright (c) 1996, 2013, Oracle and/or its affiliates. All rights reserved.
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

package rubyko.sun.rmi.transport;

import java.io.IOException;
import java.io.ObjectOutput;
import rubyko.java.rmi.MarshalException;
import rubyko.java.rmi.NoSuchObjectException;
import rubyko.java.rmi.Remote;
import rubyko.java.rmi.RemoteException;
import rubyko.java.rmi.server.LogStream;
import rubyko.java.rmi.server.ObjID;
import rubyko.java.rmi.server.RemoteCall;
import rubyko.java.rmi.server.RemoteServer;
import rubyko.java.rmi.server.ServerNotActiveException;
import java.security.AccessControlContext;
import rubyko.sun.rmi.server.Dispatcher;
import rubyko.sun.rmi.server.UnicastServerRef;

/**
 * Transport abstraction for enabling communication between different
 * VMs.
 *
 * @author Ann Wollrath
 */
@SuppressWarnings("deprecation")
public abstract class Transport {


    /** References the current transport when a call is being serviced */
    private static final ThreadLocal<Transport> currentTransport = new ThreadLocal<>();

    /** ObjID for DGCImpl */
    private static final ObjID dgcID = new ObjID(ObjID.DGC_ID);

    /**
     * Returns a <I>Channel</I> that generates connections to the
     * endpoint <I>ep</I>. A Channel is an object that creates and
     * manages connections of a particular type to some particular
     * address space.
     * @param ep the endpoint to which connections will be generated.
     * @return the channel or null if the transport cannot
     * generate connections to this endpoint
     */
    public abstract Channel getChannel(Endpoint ep);

    /**
     * Removes the <I>Channel</I> that generates connections to the
     * endpoint <I>ep</I>.
     */
    public abstract void free(Endpoint ep);

    /**
     * Export the object so that it can accept incoming calls.
     */
    public void exportObject(Target target) throws RemoteException {
        target.setExportedTransport(this);
        ObjectTable.putTarget(target);
    }

    /**
     * Invoked when an object that was exported on this transport has
     * become unexported, either by being garbage collected or by
     * being explicitly unexported.
     **/
    protected void targetUnexported() { }

    /**
     * Returns the current transport if a call is being serviced, otherwise
     * returns null.
     **/
    static Transport currentTransport() {
        return currentTransport.get();
    }

    /**
     * Verify that the current access control context has permission to accept
     * the connection being dispatched by the current thread.  The current
     * access control context is passed as a parameter to avoid the overhead of
     * an additional call to AccessController.getContext.
     */
    protected abstract void checkAcceptPermission(AccessControlContext acc);

    /**
     * Service an incoming remote call. When a message arrives on the
     * connection indicating the beginning of a remote call, the
     * threads are required to call the <I>serviceCall</I> method of
     * their transport.  The default implementation of this method
     * locates and calls the dispatcher object.  Ordinarily a
     * transport implementation will not need to override this method.
     * At the entry to <I>tr.serviceCall(conn)</I>, the connection's
     * input stream is positioned at the start of the incoming
     * message.  The <I>serviceCall</I> method processes the incoming
     * remote invocation and sends the result on the connection's
     * output stream.  If it returns "true", then the remote
     * invocation was processed without error and the transport can
     * cache the connection.  If it returns "false", a protocol error
     * occurred during the call, and the transport should destroy the
     * connection.
     */
    public boolean serviceCall(final RemoteCall call) {
        try {
            /* read object id */
            final Remote impl;
            ObjID id;

            try {
                id = ObjID.read(call.getInputStream());
            } catch (java.io.IOException e) {
                throw new MarshalException("unable to read objID", e);
            }

            /* get the remote object */
            Transport transport = id.equals(dgcID) ? null : this;
            Target target =
                ObjectTable.getTarget(new ObjectEndpoint(id, transport));

            if (target == null || (impl = target.getImpl()) == null) {
                throw new NoSuchObjectException("no such object in table");
            }

            final Dispatcher disp = target.getDispatcher();
            target.incrementCallCount();
            try {

                final AccessControlContext acc =
                    target.getAccessControlContext();
                ClassLoader ccl = target.getContextClassLoader();

                Thread t = Thread.currentThread();
                ClassLoader savedCcl = t.getContextClassLoader();

                try {
                    t.setContextClassLoader(ccl);
                    currentTransport.set(this);
                    try {
                        java.security.AccessController.doPrivileged(
                            new java.security.PrivilegedExceptionAction<Void>() {
                            public Void run() throws IOException {
                                checkAcceptPermission(acc);
                                disp.dispatch(impl, call);
                                return null;
                            }
                        }, acc);
                    } catch (java.security.PrivilegedActionException pae) {
                        throw (IOException) pae.getException();
                    }
                } finally {
                    t.setContextClassLoader(savedCcl);
                    currentTransport.set(null);
                }

            } catch (IOException ex) {
                return false;
            } finally {
                target.decrementCallCount();
            }

        } catch (RemoteException e) {


            /* We will get a RemoteException if either a) the objID is
             * not readable, b) the target is not in the object table, or
             * c) the object is in the midst of being unexported (note:
             * NoSuchObjectException is thrown by the incrementCallCount
             * method if the object is being unexported).  Here it is
             * relatively safe to marshal an exception to the client
             * since the client will not have seen a return value yet.
             */
            try {
                ObjectOutput out = call.getResultStream(false);
                UnicastServerRef.clearStackTraces(e);
                out.writeObject(e);
                call.releaseOutputStream();

            } catch (IOException ie) {
                return false;
            }
        }

        return true;
    }
}
