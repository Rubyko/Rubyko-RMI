/*
 * Copyright (c) 1999, 2004, Oracle and/or its affiliates. All rights reserved.
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

/*
 * Licensed Materials - Property of IBM
 * RMI-IIOP v1.0
 * Copyright IBM Corp. 1998 1999  All Rights Reserved
 *
 */

package rubyko.com.sun.corba.se.impl.util;

import org.omg.CORBA.SystemException;
import org.omg.CORBA.CompletionStatus;
import org.omg.CORBA.BAD_OPERATION;
import org.omg.CORBA.BAD_INV_ORDER;
import org.omg.CORBA.BAD_PARAM;
import org.omg.CORBA.ORB;
import org.omg.CORBA.Any;
import org.omg.CORBA.TypeCode;
import org.omg.CORBA.Principal;
import org.omg.CORBA.portable.InputStream;
import org.omg.CORBA.portable.OutputStream;
import org.omg.CORBA.portable.BoxedValueHelper;
import org.omg.CORBA.portable.ValueFactory;
import org.omg.CORBA.portable.Streamable;
import org.omg.CORBA.portable.Delegate;


import java.util.Hashtable;
import java.util.NoSuchElementException;

import java.rmi.Remote;
import java.rmi.NoSuchObjectException;
import java.rmi.RemoteException;
import java.rmi.server.RemoteStub;

import javax.rmi.PortableRemoteObject;
import javax.rmi.CORBA.Stub;
import javax.rmi.CORBA.Tie;
import javax.rmi.CORBA.Util;

import java.io.Serializable;
import java.io.File;
import java.io.FileInputStream;

import org.omg.PortableServer.POA;


/**
 *  Handy class full of static functions.
 */
public final class Utility {

    public static final String STUB_PREFIX = "_";
    public static final String RMI_STUB_SUFFIX = "_Stub";
    public static final String DYNAMIC_STUB_SUFFIX = "_DynamicStub" ;
    public static final String IDL_STUB_SUFFIX = "Stub";
    public static final String TIE_SUFIX = "_Tie";


    /**
     * Create an RMI tie name.
     */
    public static String tieName (String className)
    {
        return "bla";
         //   PackagePrefixChecker.hasOffendingPrefix(tieNameForCompiler(className)) ?
         //   PackagePrefixChecker.packagePrefix() + tieNameForCompiler(className) :
          //  tieNameForCompiler(className);
    }
}

class StubEntry {
    org.omg.CORBA.Object stub;
    boolean mostDerived;

    StubEntry(org.omg.CORBA.Object stub, boolean mostDerived) {
        this.stub = stub;
        this.mostDerived = mostDerived;
    }
}
