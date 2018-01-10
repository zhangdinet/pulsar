/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.pulsar.functions.worker;

import java.io.IOException;
import java.util.function.Function;

import com.google.protobuf.InvalidProtocolBufferException;
import lombok.extern.slf4j.Slf4j;
import org.apache.pulsar.client.api.Message;
import org.apache.pulsar.client.api.PulsarClientException;
import org.apache.pulsar.client.api.Reader;
import org.apache.pulsar.functions.worker.request.DeregisterRequest;
import org.apache.pulsar.functions.worker.request.MarkerRequest;
import org.apache.pulsar.functions.worker.request.UpdateRequest;

@Slf4j
public class FunctionMetaDataTopicTailer
        implements java.util.function.Consumer<Message>, Function<Throwable, Void>, AutoCloseable {

    private final FunctionRuntimeManager functionRuntimeManager;
    private final Reader reader;

    public FunctionMetaDataTopicTailer(FunctionRuntimeManager functionRuntimeManager,
                                       Reader reader)
            throws PulsarClientException {
        this.functionRuntimeManager = functionRuntimeManager;
        this.reader = reader;
    }

    public void start() {
        initialize();
        receiveOne();
    }

    public void initialize() {
        log.info("Initializing Metadata state...");
        this.functionRuntimeManager.sendIntializationMarker();
    }

    private void receiveOne() {
        reader.readNextAsync()
            .thenAccept(this)
            .exceptionally(this);
    }

    @Override
    public void close() {
        log.info("Stopping function state consumer");
        try {
            reader.close();
        } catch (IOException e) {
            log.error("Failed to stop function state consumer", e);
        }
        log.info("Stopped function state consumer");
    }

    @Override
    public void accept(Message msg) {

        org.apache.pulsar.functions.proto.ServiceRequest.Request serviceRequest;

        try {
            serviceRequest = org.apache.pulsar.functions.proto.ServiceRequest.Request.parseFrom(msg.getData());
        } catch (InvalidProtocolBufferException e) {
            log.error("Received bad service request at message {}", msg.getMessageId(), e);
            // TODO: find a better way to handle bad request
            throw new RuntimeException(e);
        }
        if (log.isDebugEnabled()) {
            log.debug("Received Service Request: {}", serviceRequest);
        }

        switch(serviceRequest.getServiceRequestType()) {
            case MARKER:
                this.functionRuntimeManager.processInitializeMarker(MarkerRequest.of(serviceRequest));
                break;
            case UPDATE:
                this.functionRuntimeManager.processUpdate(UpdateRequest.of(serviceRequest));
                break;
            case DELETE:
                this.functionRuntimeManager.proccessDeregister(DeregisterRequest.of(serviceRequest));
                break;
            default:
                log.warn("Received request with unrecognized type: {}", serviceRequest);
        }

        // receive next request
        receiveOne();
    }

    @Override
    public Void apply(Throwable cause) {
        log.error("Failed to retrieve messages from function state topic", cause);
        // TODO: find a better way to handle consumer functions
        throw new RuntimeException(cause);
    }
}