/*
 *
 * ** BEGIN LICENSE BLOCK *****
 * Version: MPL 1.1/GPL 2.0/LGPL 2.1
 *
 * The contents of this file are subject to the Mozilla Public License Version
 * 1.1 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 * http://www.mozilla.org/MPL/
 *
 * Software distributed under the License is distributed on an "AS IS" basis,
 * WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
 * for the specific language governing rights and limitations under the
 * License.
 *
 * The Initial Developer of the Original Code is
 * Agfa Healthcare.
 * Portions created by the Initial Developer are Copyright (C) 2016
 * the Initial Developer. All Rights Reserved.
 *
 * Contributor(s):
 * See @authors listed below
 *
 * Alternatively, the contents of this file may be used under the terms of
 * either the GNU General Public License Version 2 or later (the "GPL"), or
 * the GNU Lesser General Public License Version 2.1 or later (the "LGPL"),
 * in which case the provisions of the GPL or the LGPL are applicable instead
 * of those above. If you wish to allow use of your version of this file only
 * under the terms of either the GPL or the LGPL, and not to allow others to
 * use your version of this file under the terms of the MPL, indicate your
 * decision by deleting the provisions above and replace them with the notice
 * and other provisions required by the GPL or the LGPL. If you do not delete
 * the provisions above, a recipient may use your version of this file under
 * the terms of any one of the MPL, the GPL or the LGPL.
 *
 * **** END LICENSE BLOCK *****
 *
 */

package org.dcm4che.test.remote.serverside;

import org.dcm4che.test.remote.Base64;
import org.dcm4che.test.remote.*;

import javax.enterprise.context.spi.CreationalContext;
import javax.enterprise.inject.spi.AnnotatedType;
import javax.enterprise.inject.spi.BeanManager;
import javax.inject.Inject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.util.*;


/**
 * @author rawmahn
 */
public class WarpUnitInsider implements WarpUnitInsiderREST {

    @Inject
    BeanManager beanManager;

    @SuppressWarnings("unchecked")
    @Override
    public String warpAndRun(RemoteRequestJSON requestJSON) {

        // unbase64 the bytecode
        Map<String, byte[]> classNameToByteCode = new HashMap<>();
        for (Map.Entry<String, String> nameToBase64Entry : requestJSON.classes.entrySet()) {
            try {
                classNameToByteCode.put(nameToBase64Entry.getKey(), Base64.fromBase64(nameToBase64Entry.getValue()));
            } catch (IOException e) {
                throw new RuntimeException("base64 issue", e);
            }
        }

        // prepare classloader
        ClassLoader parentClassLoader = WarpUnitClassLoader.class.getClassLoader();
        WarpUnitClassLoader classLoader = new WarpUnitClassLoader(parentClassLoader, classNameToByteCode);

        // create main object
        Class myObjectClass = null;
        Object object = null;
        try {
            // classloading/reflection magic
            myObjectClass = classLoader.loadClass(requestJSON.primaryClassName);
            object = myObjectClass.newInstance();

            // cdi magic
            CreationalContext creationalContext = beanManager.createCreationalContext(null);
            AnnotatedType annotatedType = beanManager.createAnnotatedType(object.getClass());
            beanManager
                    .createInjectionTarget(annotatedType)
                    .inject(object, creationalContext);

        } catch (Exception e) {
            throw new RuntimeException("instantiation issue", e);
        }

        // find and call appropriate method
        Object result = null;
        boolean foundMethod = false;

        List<Method> methods = new ArrayList<>();
        methods.addAll(Arrays.asList(myObjectClass.getMethods()));
        methods.addAll(Arrays.asList(myObjectClass.getDeclaredMethods()));

        for (Method method : methods) {
            if (method.getName().equals(requestJSON.methodName)) {
                try {
                    // make sure we can do lambdas
                    method.setAccessible(true);

                    result = method.invoke(object, (Object[]) DeSerializer.deserialize(Base64.fromBase64(requestJSON.args)));
                } catch (Exception e) {
                    // send the exception back to the caller - it will recognize it and rethrow on it's side

                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    PrintStream ps = new PrintStream(baos);
                    e.printStackTrace(ps);
                    String niceStackTrace = baos.toString();

                    result = new RemoteExecutionException(niceStackTrace);
                }
                foundMethod = true;
                break;
            }
        }

        if (!foundMethod) result = new RuntimeException("method " + requestJSON.methodName + " not found");

        return Base64.toBase64(DeSerializer.serialize(result));

    }


}
