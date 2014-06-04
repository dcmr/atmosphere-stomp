/*
 * Copyright 2014 Jeanfrancois Arcand
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */


package org.atmosphere.cpr;

import org.atmosphere.cpr.packages.StompEndpointProcessor;
import org.atmosphere.stomp.Subscriptions;
import org.atmosphere.stomp.interceptor.FrameInterceptor;
import org.atmosphere.stomp.test.StompBusinessService;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.testng.annotations.BeforeMethod;

import javax.servlet.ServletConfig;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import java.io.IOException;
import java.util.Enumeration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import static org.mockito.Matchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.testng.Assert.assertTrue;

/**
 * <p>
 * Base test class.
 * </p>
 *
 * @author Guillaume DROUET
 * @since 0.2
 * @version 1.0
 */
public class StompTest {

    AtmosphereFramework framework;
    AtmosphereConfig config;
    AsynchronousProcessor processor;

    /**
     * Action injected by formatter. Could be change on the fly inside tests.
     */
    org.atmosphere.stomp.protocol.Action action = org.atmosphere.stomp.protocol.Action.SEND;

    /**
     * <p>
     * Initializes framework.
     * </p>
     *
     * @throws Throwable if test fails
     */
    @BeforeMethod
    public void create() throws Throwable {
        framework = new AtmosphereFramework();

        // Detect processor
        framework.addCustomAnnotationPackage(StompEndpointProcessor.class);

        // Detect service
        framework.addAnnotationPackage(StompBusinessService.class);
        //framework.addAnnotationPackage(HeartbeatTest.HeartbeatStompEndpoint.class);

        // Global handler: mandatory
        framework.init(new ServletConfig() {
            @Override
            public String getServletName() {
                return "void";
            }

            @Override
            public ServletContext getServletContext() {
                return mock(ServletContext.class);
            }

            @Override
            public String getInitParameter(final String name) {
                return null;
            }

            @Override
            public Enumeration<String> getInitParameterNames() {
                return null;
            }
        });

        config = framework.getAtmosphereConfig();

        processor = new AsynchronousProcessor(config) {
            @Override
            public org.atmosphere.cpr.Action service(AtmosphereRequest req, AtmosphereResponse res) throws IOException, ServletException {
                return action(req, res);
            }
        };

        framework.setAsyncSupport(processor);

        // Configure interceptor
        framework.interceptor(new FrameInterceptor());
    }

    /**
     * <p>
     * Returns the frame to read.
     * </p>
     *
     * @param destination the destination's header value
     * @return the string representation
     */
    String toRead(final String destination) {
        return action.toString()
                + "\n"
                + "destination:"
                + destination
                + "\n"
                + "id:"
                + 1
                + "\n"
                + "content-type:text/plain\n"
                + "\n"
                + String.format("{\"timestamp\":%d, \"message\":\"%s\"}", System.currentTimeMillis(), "hello");
    }

    /**
     * <p>
     * Builds a new request.
     * </p>
     *
     * @param destination the destination's header value in the request frame
     * @return the request
     */
    AtmosphereRequest newRequest(final String destination) {
        return newRequest(destination, toRead(destination));
    }

    /**
     * <p>
     * Builds a new request.
     * </p>
     *
     * @param destination the destination's header value in the request frame
     * @return the request
     */
    AtmosphereRequest newRequest(final String destination, final String body) {
        final AtmosphereRequest req = new AtmosphereRequest.Builder()
                .pathInfo(destination)
                .method("GET")
                .body(body)
                .build();

        req.setAttribute(ApplicationConfig.SUSPENDED_ATMOSPHERE_RESOURCE_UUID, "4000");

        return req;
    }

    /**
     * <p>
     * Builds a new response.
     * </p>
     *
     * @return the response
     */
    AtmosphereResponse newResponse() {
        return AtmosphereResponse.newInstance();
    }

    /**
     * <p>
     * Builds a new atmosphere resource.
     * </p>
     *
     * @param destination the destination in the request
     * @param req the request
     * @param res the response
     * @param bindToRequest {@code true} if the created resource should be added to request attributes
     * @return the resource
     * @throws Exception if creation fails
     */
    AtmosphereResource newAtmosphereResource(final String destination,
                                                     final AtmosphereRequest req,
                                                     final AtmosphereResponse res,
                                                     final boolean bindToRequest)
            throws Exception {

        // Add an AtmosphereResource that receives a message
        final AtmosphereHandler ah = mock(AtmosphereHandler.class);
        AtmosphereResource ar = framework.arFactory.find("4000");

        if (ar == null) {
            ar = new AtmosphereResourceImpl();
            final Broadcaster b = framework.getBroadcasterFactory().lookup(destination);
            ar.initialize(config, b, req, res, framework.asyncSupport, ah);
            ((AtmosphereResourceImpl) ar).transport(AtmosphereResource.TRANSPORT.WEBSOCKET);
        } else {
            ar.getRequest().body(req.body().asString());
            ar.getRequest().body(req.getInputStream());
            ((AtmosphereResourceImpl) ar).atmosphereHandler(ah);
            ((AtmosphereResourceImpl) ar).transport(AtmosphereResource.TRANSPORT.WEBSOCKET);
        }

        if (bindToRequest) {
            req.setAttribute(FrameworkConfig.INJECTED_ATMOSPHERE_RESOURCE, ar);
            framework.arFactory.resources().put(ar.uuid(), ar);
        }

        return ar;
    }

    /**
     * <p>
     * Adds the given resource to the broadcaster mapped to the given destination.
     * </p>
     *
     * @param destination the destination
     * @param ar the atmosphere resource
     */
    void addToBroadcaster(final String destination, final AtmosphereResource ar) {
        final Broadcaster b = framework.getBroadcasterFactory().lookup(destination);
        Subscriptions.getFromSession(AtmosphereResourceSessionFactory.getDefault().getSession(ar)).addSubscription("1", destination);
        b.addAtmosphereResource(ar);
    }


    /**
     * <p>
     * Sends a message at the given destination and checks that the given regex matches the message broadcasted to a
     * resource registered to the destination.
     * </p>
     *
     * @param regex the expected regex
     * @throws Exception if test fails
     */
    void runMessage(final String regex,
                    final String destination,
                    final AtmosphereRequest req,
                    final AtmosphereResponse res,
                    final boolean addToBroadcaster)
            throws Exception {
        final AtmosphereResource ar = newAtmosphereResource(destination, req, res, false);

        // Wait until message has been broadcasted
        final CountDownLatch countDownLatch = new CountDownLatch(1);
        final AtomicReference<String> broadcast = new AtomicReference<String>();

        // Release lock when message is broadcasted
        ar.getResponse().asyncIOWriter(new AsyncIOWriterAdapter() {
            @Override
            public AsyncIOWriter write(final AtmosphereResponse r, final byte[] data) throws IOException {
                broadcast.set(new String(data));
                countDownLatch.countDown();
                return this;
            }
        });

        // we also need to intercept AtmosphereHandler call
        doAnswer(new Answer() {
            @Override
            public Object answer(final InvocationOnMock invocationOnMock) throws Throwable {
                broadcast.set(String.valueOf(((AtmosphereResourceEvent) invocationOnMock.getArguments()[0]).getMessage()));
                countDownLatch.countDown();
                return null;
            }
        }).when(ar.getAtmosphereHandler()).onStateChange(any(AtmosphereResourceEvent.class));

        if (addToBroadcaster) {
            addToBroadcaster(destination, ar);
        }

        // Run interceptor
        processor.service(ar.getRequest(), ar.getResponse());

        countDownLatch.await(3, TimeUnit.SECONDS);

        // Expect that broadcaster's resource receives message from STOMP service
        assertTrue(Pattern.compile(regex, Pattern.DOTALL).matcher(broadcast.toString()).matches(), broadcast.toString());

        if (addToBroadcaster) {
            Subscriptions.getFromSession(AtmosphereResourceSessionFactory.getDefault().getSession(ar)).removeSubscription("1");
        }
    }
}