/*-
 * #%L
 * Elastic APM Java agent
 * %%
 * Copyright (C) 2018 - 2019 Elastic and contributors
 * %%
 * Licensed to Elasticsearch B.V. under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch B.V. licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 * #L%
 */
package co.elastic.apm.agent.httpclient;

import co.elastic.apm.agent.http.client.HttpClientHelper;
import co.elastic.apm.agent.impl.transaction.Span;
import co.elastic.apm.agent.impl.transaction.TraceContext;
import co.elastic.apm.agent.objectpool.Recyclable;
import org.apache.http.HttpException;
import org.apache.http.HttpHost;
import org.apache.http.HttpRequest;
import org.apache.http.RequestLine;
import org.apache.http.nio.ContentEncoder;
import org.apache.http.nio.IOControl;
import org.apache.http.nio.protocol.HttpAsyncRequestProducer;
import org.apache.http.protocol.HttpContext;

import java.io.IOException;

public class HttpAsyncRequestProducerWrapper implements HttpAsyncRequestProducer, Recyclable {
    private final ApacheHttpAsyncClientHelperImpl helper;
    private volatile HttpAsyncRequestProducer delegate;
    private Span span;

    HttpAsyncRequestProducerWrapper(ApacheHttpAsyncClientHelperImpl helper) {
        this.helper = helper;
    }

    public HttpAsyncRequestProducerWrapper with(HttpAsyncRequestProducer delegate, Span span) {
        // Order is important due to visibility - write to delegate last on this (initiating) thread
        this.span = span;
        this.delegate = delegate;
        return this;
    }

    @Override
    public HttpHost getTarget() {
        return delegate.getTarget();
    }

    @Override
    public HttpRequest generateRequest() throws IOException, HttpException {
        // first read the volatile, span will become visible as a result
        HttpRequest request = delegate.generateRequest();

        if (request != null) {
            RequestLine requestLine = request.getRequestLine();
            if (requestLine != null) {
                String method = requestLine.getMethod();
                span.withName(method).appendToName(" ");
                span.getContext().getHttp().withMethod(method).withUrl(requestLine.getUri());
            }

            request.setHeader(TraceContext.TRACE_PARENT_HEADER, span.getTraceContext().getOutgoingTraceParentHeader().toString());
        }

        HttpHost host = getTarget();
        //noinspection ConstantConditions
        if (host != null) {
            String hostname = host.getHostName();
            if (hostname != null) {
                span.appendToName(hostname);
                HttpClientHelper.setDestinationServiceDetails(span, host.getSchemeName(), hostname, host.getPort());
            }
        }

        //noinspection ConstantConditions
        return request;
    }

    @Override
    public void produceContent(ContentEncoder encoder, IOControl ioctrl) throws IOException {
        delegate.produceContent(encoder, ioctrl);
    }

    @Override
    public void requestCompleted(HttpContext context) {
        delegate.requestCompleted(context);
    }

    @Override
    public void failed(Exception ex) {
        delegate.failed(ex);
    }

    @Override
    public boolean isRepeatable() {
        return delegate.isRepeatable();
    }

    @Override
    public void resetRequest() throws IOException {
        delegate.resetRequest();
    }

    @Override
    public void close() throws IOException {
        delegate.close();
        helper.recycle(this);
    }

    @Override
    public void resetState() {
        // Order is important due to visibility - write to delegate last
        span = null;
        delegate = null;
    }
}
