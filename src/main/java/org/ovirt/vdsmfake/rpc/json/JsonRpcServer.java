package org.ovirt.vdsmfake.rpc.json;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.ovirt.vdsm.jsonrpc.client.ClientConnectionException;
import org.ovirt.vdsm.jsonrpc.client.JsonRpcRequest;
import org.ovirt.vdsm.jsonrpc.client.JsonRpcResponse;
import org.ovirt.vdsm.jsonrpc.client.ResponseBuilder;
import org.ovirt.vdsm.jsonrpc.client.reactors.Reactor;
import org.ovirt.vdsm.jsonrpc.client.reactors.ReactorClient;
import org.ovirt.vdsm.jsonrpc.client.reactors.ReactorClient.MessageListener;
import org.ovirt.vdsm.jsonrpc.client.reactors.ReactorFactory;
import org.ovirt.vdsm.jsonrpc.client.reactors.ReactorListener;
import org.ovirt.vdsm.jsonrpc.client.reactors.ReactorType;
import org.ovirt.vdsmfake.ContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JsonRpcServer {
    private static final Logger log = LoggerFactory
            .getLogger(JsonRpcServer.class);

    private final static int TIMEOUT = 5000;
    private static ReactorListener listener;
    private int jsonPort;
    private boolean encrypted;
    private String hostName;
    private ExecutorService service = Executors.newFixedThreadPool(200);

    public JsonRpcServer(String hostName, int jsonPort, boolean encrypted) {
        this.hostName = hostName;
        this.jsonPort = jsonPort;
        this.encrypted = encrypted;
    }

    public void start() {
        try {
            String hostName = System.getProperty("fake.host");

            if (hostName == null) {
                hostName = "localhost";
            }
            log.debug("Opening a Stomp server " + hostName + ":" + jsonPort);
            Reactor reactor;

            if (!encrypted) {
                reactor = ReactorFactory.getReactor(null, ReactorType.STOMP);
            } else {
                reactor = ReactorFactory.getReactor(new FakeVDSMSSLProvider(System.getProperty("fake.keystore"),
                        System.getProperty("fake.truststore"), "changeit", null), ReactorType.STOMP);
            }

            final Future<ReactorListener> futureListener =
                    reactor.createListener(hostName, jsonPort, new ReactorListener.EventListener() {

                        @Override
                        public void onAcccept(final ReactorClient client) {
                            client.addEventListener(new MessageListener() {
                                // you can provide your implementation of MessageListener
                                @Override
                                public void onMessageReceived(byte[] message) {
                                    MessageHandler handler = new MessageHandler(client, message);
                                    // handler.run();
                                    service.submit(handler);
                                }
                            });
                        }
                    });

            listener = futureListener.get(TIMEOUT, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            log.error("Failure to start json server socket", e);
        }
    }

    public static void shutdown() {
        listener.close();
    }

    private class MessageHandler implements Runnable {
        private ReactorClient client;
        private byte[] message;

        public MessageHandler(ReactorClient client, byte[] message) {
            super();
            this.client = client;
            this.message = message;
        }

        public void run() {
            JsonRpcRequest request = null;
            try {
                request = JsonRpcRequest.fromByteArray(message);

                ContextHolder.init();
                if (client.getRetryPolicy().getIdentifier() != null){
                    ContextHolder.setServerName(client.getRetryPolicy().getIdentifier());
                    log.debug("client policy identifier {}", client.getRetryPolicy().getIdentifier());
                }else{
                    ContextHolder.setServerName(Integer.toString(client.hashCode()));
                    log.error("client identifier were not found, using hash");
                }

                ResponseBuilder builder = new ResponseBuilder(request.getId());
                String methodName = request.getMethod();
                builder =
                        CommandFactory.createCommand(methodName).run(request.getParams(),
                                builder);
                send(builder.build(), request.getMethod());
            } catch (Throwable e) {
                log.error("Failure in processing request", e);
                Map<String, Object> error = new HashMap<>();
                error.put("code", 100);
                error.put("message", e.getMessage());

                if (request != null) {
                    send(new ResponseBuilder(request.getId()).withError(error).build(), request.getMethod());
                }
            }
        }

        private void send(JsonRpcResponse response, String method) {
            if (log.isInfoEnabled()) {
                log.info("Request is " + method + " got response "
                        + new String(response.toByteArray()));
            }
            try {
                client.sendMessage(response.toByteArray());
            } catch (ClientConnectionException e) {
                log.error("Failure in sending response", e);
            }
        }
    }
}
