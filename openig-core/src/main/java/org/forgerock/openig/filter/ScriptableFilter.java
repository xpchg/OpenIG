/*
 * The contents of this file are subject to the terms of the Common Development and
 * Distribution License (the License). You may not use this file except in compliance with the
 * License.
 *
 * You can obtain a copy of the License at legal/CDDLv1.0.txt. See the License for the
 * specific language governing permission and limitations under the License.
 *
 * When distributing Covered Software, include this CDDL Header Notice in each file and include
 * the License file at legal/CDDLv1.0.txt. If applicable, add the following below the CDDL
 * Header, with the fields enclosed by brackets [] replaced by your own identifying
 * information: "Portions copyright [year] [name of copyright owner]".
 *
 * Copyright 2014-2015 ForgeRock AS.
 */
package org.forgerock.openig.filter;

import static org.forgerock.openig.el.Bindings.bindings;

import org.forgerock.http.Filter;
import org.forgerock.http.Handler;
import org.forgerock.http.protocol.Request;
import org.forgerock.http.protocol.Response;
import org.forgerock.openig.heap.Heap;
import org.forgerock.openig.heap.HeapException;
import org.forgerock.openig.script.AbstractScriptableHeapObject;
import org.forgerock.openig.script.Script;
import org.forgerock.services.context.Context;
import org.forgerock.util.promise.NeverThrowsException;
import org.forgerock.util.promise.Promise;

/**
 * A scriptable filter. This filter acts as a simple wrapper around the
 * scripting engine. Scripts are provided with the following variable bindings:
 * <ul>
 * <li>{@link java.util.Map globals} - the Map of global variables which persist across
 * successive invocations of the script
 * <li>{@link org.forgerock.services.context.Context context} - the associated request context
 * <li>{@link Request request} - the HTTP request
 * <li>{@link org.forgerock.http.Client http} - an HTTP client which may be used for
 * performing outbound HTTP requests
 * <li>{@link org.forgerock.openig.ldap.LdapClient ldap} - an OpenIG LDAP client which may be used for
 * performing LDAP requests such as LDAP authentication
 * <li>{@link org.forgerock.openig.log.Logger logger} - the OpenIG logger
 * <li>{@link Handler next} - the next handler in the filter chain.
 * </ul>
 * Like Java based filters, scripts are free to choose whether or not they
 * forward the request to the next handler or, instead, return a response
 * immediately.
 * <p>Contains also easy access to {@code attributes} from the {@link org.forgerock.services.context.AttributesContext},
 * e.g: {@code attributes.user = "jackson"}, instead of {@code contexts.attributes.attributes.user = "jackson"}.
 * <p>In the same way, it gives access to {@code session} from the {@link org.forgerock.http.session.SessionContext},
 * for example, you can use: {@code session.put(...)}, instead of {@code contexts.session.session.put(...)}.
 * <p>
 * <b>NOTE:</b> at the moment only Groovy is supported.
 * <p><b>NOTE:</b> As of OpenIG 4.0, {@code exchange.request} and {@code exchange.response} are not set anymore.
 */
public class ScriptableFilter extends AbstractScriptableHeapObject implements Filter {

    @Override
    public Promise<Response, NeverThrowsException> filter(final Context context,
                                                          final Request request,
                                                          final Handler next) {
        // Delegates filtering to the script.
        return runScript(bindings(context, request), next, context);
    }

    /**
     * Creates and initializes a scriptable filter in a heap environment.
     */
    public static class Heaplet extends AbstractScriptableHeaplet {
        @Override
        public ScriptableFilter newInstance(Script script, Heap heap) throws HeapException {
            return new ScriptableFilter(script, heap);
        }
    }

    ScriptableFilter(final Script compiledScript, Heap heap) {
        super(compiledScript, heap);
    }
}
