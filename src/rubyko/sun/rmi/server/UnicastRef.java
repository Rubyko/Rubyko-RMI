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

package rubyko.sun.rmi.server;

import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.lang.reflect.Method;
import rubyko.java.rmi.MarshalException;
import rubyko.java.rmi.Remote;
import rubyko.java.rmi.RemoteException;
import rubyko.java.rmi.UnmarshalException;
import rubyko.java.rmi.server.Operation;
import rubyko.java.rmi.server.RemoteCall;
import rubyko.java.rmi.server.RemoteObject;
import rubyko.java.rmi.server.RemoteRef;
import rubyko.sun.rmi.transport.Connection;
import rubyko.sun.rmi.transport.LiveRef;
import rubyko.sun.rmi.transport.StreamRemoteCall;

/**
 * NOTE: There is a JDK-internal dependency on the existence of this
 * class's getLiveRef method (as it is inherited by UnicastRef2) in
 * the implementation of javax.management.remote.rmi.RMIConnector.
 */
@SuppressWarnings("deprecation")
public class UnicastRef implements RemoteRef {

    private static final long serialVersionUID = 8258372400816541186L;

    protected LiveRef ref;

    /**
     * Create a new (empty) Unicast remote reference.
     */
    public UnicastRef() {
    }

    /**
     * Create a new Unicast RemoteRef.
     */
    public UnicastRef(LiveRef liveRef) {
        ref = liveRef;
    }

    /**
     * Returns the current value of this UnicastRef's underlying
     * LiveRef.
     *
     * NOTE: There is a JDK-internal dependency on the existence of
     * this method (as it is inherited by UnicastRef) in the
     * implementation of javax.management.remote.rmi.RMIConnector.
     **/
    public LiveRef getLiveRef() {
        return ref;
    }

    /**
     * Invoke a method. This form of delegating method invocation
     * to the reference allows the reference to take care of
     * setting up the connection to the remote host, marshalling
     * some representation for the method and parameters, then
     * communicating the method invocation to the remote host.
     * This method either returns the result of a method invocation
     * on the remote object which resides on the remote host or
     * throws a RemoteException if the call failed or an
     * application-level exception if the remote invocation throws
     * an exception.
     *
     * @param obj the proxy for the remote object
     * @param method the method to be invoked
     * @param params the parameter list
     * @param opnum  a hash that may be used to represent the method
     * @since 1.2
     */
    public Object invoke(Remote obj,
                         Method method,
                         Object[] params,
                         long opnum)
        throws Exception
    {

        Connection conn = ref.getChannel().newConnection();
        RemoteCall call = null;
        boolean reuse = true;

        /* If the call connection is "reused" early, remember not to
         * reuse again.
         */
        boolean alreadyFreed = false;

        try {

            // create call context
            call = new StreamRemoteCall(conn, ref.getObjID(), -1, opnum);

            // marshal parameters
            try {
                ObjectOutput out = call.getOutputStream();
                marshalCustomCallData(out);
                Class<?>[] types = method.getParameterTypes();
                for (int i = 0; i < types.length; i++) {
                    marshalValue(types[i], params[i], out);
                }
            } catch (IOException e) {
                throw new MarshalException("error marshalling arguments", e);
            }

            // unmarshal return
            call.executeCall();

            try {
                Class<?> rtype = method.getReturnType();
                if (rtype == void.class)
                    return null;
                ObjectInput in = call.getInputStream();

                /* StreamRemoteCall.done() does not actually make use
                 * of conn, therefore it is safe to reuse this
                 * connection before the dirty call is sent for
                 * registered refs.
                 */
                Object returnValue = unmarshalValue(rtype, in);

                /* we are freeing the connection now, do not free
                 * again or reuse.
                 */
                alreadyFreed = true;

                /* Free the call's connection early. */
                ref.getChannel().free(conn, true);

                return returnValue;

            } catch (IOException e) {
                throw new UnmarshalException("error unmarshalling return", e);
            } catch (ClassNotFoundException e) {
                throw new UnmarshalException("error unmarshalling return", e);
            } finally {
                try {
                    call.done();
                } catch (IOException e) {
                    /* WARNING: If the conn has been reused early,
                     * then it is too late to recover from thrown
                     * IOExceptions caught here. This code is relying
                     * on StreamRemoteCall.done() not actually
                     * throwing IOExceptions.
                     */
                    reuse = false;
                }
            }

        } catch (RuntimeException e) {
            /*
             * Need to distinguish between client (generated by the
             * invoke method itself) and server RuntimeExceptions.
             * Client side RuntimeExceptions are likely to have
             * corrupted the call connection and those from the server
             * are not likely to have done so.  If the exception came
             * from the server the call connection should be reused.
             */
            if ((call == null) ||
                (((StreamRemoteCall) call).getServerException() != e))
            {
                reuse = false;
            }
            throw e;

        } catch (RemoteException e) {
            /*
             * Some failure during call; assume connection cannot
             * be reused.  Must assume failure even if ServerException
             * or ServerError occurs since these failures can happen
             * during parameter deserialization which would leave
             * the connection in a corrupted state.
             */
            reuse = false;
            throw e;

        } catch (Error e) {
            /* If errors occurred, the connection is most likely not
             *  reusable.
             */
            reuse = false;
            throw e;

        } finally {

            /* alreadyFreed ensures that we do not log a reuse that
             * may have already happened.
             */
            if (!alreadyFreed) {
                ref.getChannel().free(conn, reuse);
            }
        }
    }

    protected void marshalCustomCallData(ObjectOutput out) throws IOException
    {}

    /**
     * Marshal value to an ObjectOutput sink using RMI's serialization
     * format for parameters or return values.
     */
    protected static void marshalValue(Class<?> type, Object value,
                                       ObjectOutput out)
        throws IOException
    {
        if (type.isPrimitive()) {
            if (type == int.class) {
                out.writeInt(((Integer) value).intValue());
            } else if (type == boolean.class) {
                out.writeBoolean(((Boolean) value).booleanValue());
            } else if (type == byte.class) {
                out.writeByte(((Byte) value).byteValue());
            } else if (type == char.class) {
                out.writeChar(((Character) value).charValue());
            } else if (type == short.class) {
                out.writeShort(((Short) value).shortValue());
            } else if (type == long.class) {
                out.writeLong(((Long) value).longValue());
            } else if (type == float.class) {
                out.writeFloat(((Float) value).floatValue());
            } else if (type == double.class) {
                out.writeDouble(((Double) value).doubleValue());
            } else {
                throw new Error("Unrecognized primitive type: " + type);
            }
        } else {
            out.writeObject(value);
        }
    }

    /**
     * Unmarshal value from an ObjectInput source using RMI's serialization
     * format for parameters or return values.
     */
    protected static Object unmarshalValue(Class<?> type, ObjectInput in)
        throws IOException, ClassNotFoundException
    {
        if (type.isPrimitive()) {
            if (type == int.class) {
                return Integer.valueOf(in.readInt());
            } else if (type == boolean.class) {
                return Boolean.valueOf(in.readBoolean());
            } else if (type == byte.class) {
                return Byte.valueOf(in.readByte());
            } else if (type == char.class) {
                return Character.valueOf(in.readChar());
            } else if (type == short.class) {
                return Short.valueOf(in.readShort());
            } else if (type == long.class) {
                return Long.valueOf(in.readLong());
            } else if (type == float.class) {
                return Float.valueOf(in.readFloat());
            } else if (type == double.class) {
                return Double.valueOf(in.readDouble());
            } else {
                throw new Error("Unrecognized primitive type: " + type);
            }
        } else {
            return in.readObject();
        }
    }

    /**
     * Create an appropriate call object for a new call on this object.
     * Passing operation array and index, allows the stubs generator to
     * assign the operation indexes and interpret them. The RemoteRef
     * may need the operation to encode in for the call.
     */
    public RemoteCall newCall(RemoteObject obj, Operation[] ops, int opnum,
                              long hash)
        throws RemoteException
    {

        Connection conn = ref.getChannel().newConnection();
        try {

            RemoteCall call =
                new StreamRemoteCall(conn, ref.getObjID(), opnum, hash);
            try {
                marshalCustomCallData(call.getOutputStream());
            } catch (IOException e) {
                throw new MarshalException("error marshaling " +
                                           "custom call data");
            }
            return call;
        } catch (RemoteException e) {
            ref.getChannel().free(conn, false);
            throw e;
        }
    }

    /**
     * Invoke makes the remote call present in the RemoteCall object.
     *
     * Invoke will raise any "user" exceptions which
     * should pass through and not be caught by the stub.  If any
     * exception is raised during the remote invocation, invoke should
     * take care of cleaning up the connection before raising the
     * "user" or remote exception.
     */
    public void invoke(RemoteCall call) throws Exception {
        try {

            call.executeCall();

        } catch (RemoteException e) {
            /*
             * Call did not complete; connection can't be reused.
             */
            free(call, false);
            throw e;

        } catch (Error e) {
            /* If errors occurred, the connection is most likely not
             *  reusable.
             */
            free(call, false);
            throw e;

        } catch (RuntimeException e) {
            /*
             * REMIND: Since runtime exceptions are no longer wrapped,
             * we can't assue that the connection was left in
             * a reusable state. Is this okay?
             */
            free(call, false);
            throw e;

        } catch (Exception e) {
            /*
             * Assume that these other exceptions are user exceptions
             * and leave the connection in a reusable state.
             */
            free(call, true);
            /* reraise user (and unknown) exceptions. */
            throw e;
        }

        /*
         * Don't free the connection if an exception did not
         * occur because the stub needs to unmarshal the
         * return value. The connection will be freed
         * by a call to the "done" method.
         */
    }

    /**
     * Private method to free a connection.
     */
    private void free(RemoteCall call, boolean reuse) throws RemoteException {
        Connection conn = ((StreamRemoteCall)call).getConnection();
        ref.getChannel().free(conn, reuse);
    }

    /**
     * Done should only be called if the invoke returns successfully
     * (non-exceptionally) to the stub. It allows the remote reference to
     * clean up (or reuse) the connection.
     */
    public void done(RemoteCall call) throws RemoteException {

        /* Done only uses the connection inside the call to obtain the
         * channel the connection uses.  Once all information is read
         * from the connection, the connection may be freed.
         */
        /* Free the call connection early. */
        free(call, true);

        try {
            call.done();
        } catch (IOException e) {
            /* WARNING: If the conn has been reused early, then it is
             * too late to recover from thrown IOExceptions caught
             * here. This code is relying on StreamRemoteCall.done()
             * not actually throwing IOExceptions.
             */
        }
    }



    /**
     * Returns the class of the ref type to be serialized
     */
    public String getRefClass(ObjectOutput out) {
        return "UnicastRef";
    }

    /**
     * Write out external representation for remote ref.
     */
    public void writeExternal(ObjectOutput out) throws IOException {
        ref.write(out, false);
    }

    /**
     * Read in external representation for remote ref.
     * @exception ClassNotFoundException If the class for an object
     * being restored cannot be found.
     */
    public void readExternal(ObjectInput in)
        throws IOException, ClassNotFoundException
    {
        ref = LiveRef.read(in, false);
    }

    //----------------------------------------------------------------------;
    /**
     * Method from object, forward from RemoteObject
     */
    public String remoteToString() {
        return Util.getUnqualifiedName(getClass()) + " [liveRef: " + ref + "]";
    }

    /**
     * default implementation of hashCode for remote objects
     */
    public int remoteHashCode() {
        return ref.hashCode();
    }

    /** default implementation of equals for remote objects
     */
    public boolean remoteEquals(RemoteRef sub) {
        if (sub instanceof UnicastRef)
            return ref.remoteEquals(((UnicastRef)sub).ref);
        return false;
    }
}
