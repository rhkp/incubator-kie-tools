/*
 * Copyright 2009 JBoss, a divison Red Hat, Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jboss.errai.bus.client;

import com.google.gwt.core.client.GWT;
import com.google.gwt.dom.client.Style;
import com.google.gwt.event.dom.client.ClickEvent;
import com.google.gwt.event.dom.client.ClickHandler;
import com.google.gwt.http.client.*;
import com.google.gwt.user.client.Timer;
import com.google.gwt.user.client.Window;
import com.google.gwt.user.client.ui.*;
import org.jboss.errai.bus.client.ext.ExtensionsLoader;
import org.jboss.errai.bus.client.json.JSONUtilCli;
import org.jboss.errai.bus.client.protocols.BusCommands;
import org.jboss.errai.bus.client.protocols.MessageParts;

import java.util.*;

import static org.jboss.errai.bus.client.CommandMessage.create;
import static org.jboss.errai.bus.client.json.JSONUtilCli.decodePayload;
import static org.jboss.errai.bus.client.json.JSONUtilCli.encodeMap;
import static org.jboss.errai.bus.client.protocols.BusCommands.RemoteSubscribe;
import static org.jboss.errai.bus.client.protocols.MessageParts.*;

/**
 * The default client <tt>MessageBus</tt> implementation.  This bus runs in the browser and automatically federates
 * with the server immediately upon initialization.
 */
public class ClientMessageBusImpl implements ClientMessageBus {
    private static final int HEARTBEAT_DELAY = 20000;

    /* The encoded URL to be used for the bus */
    private static final String SERVICE_ENTRY_POINT = "in.erraiBus";

    /* ArrayList of all subscription listeners */
    private List<SubscribeListener> onSubscribeHooks = new ArrayList<SubscribeListener>();

    /* ArrayList of all unsubscription listeners */
    private List<UnsubscribeListener> onUnsubscribeHooks = new ArrayList<UnsubscribeListener>();

    /* Used to build the HTTP POST request */
    private RequestBuilder sendBuilder;

    /* Used to build the HTTP GET request */
    private final RequestBuilder recvBuilder;

    /* Map of subjects to subscriptions  */
    private final Map<String, List<Object>> subscriptions = new HashMap<String, List<Object>>();

    /* Outgoing queue of messages to be transmitted */
    private final Queue<String> outgoingQueue = new LinkedList<String>();

    /* True if transmitting is in process */
    private boolean transmitting = false;

    /* Map of subjects to references registered in this session */
    private Map<String, Set<Object>> registeredInThisSession = new HashMap<String, Set<Object>>();

    /* A list of {@link Runnable} initialization tasks to be executed after the bus has successfully finished it's
     * initialization and is now communicating with the remote bus. */
    private ArrayList<Runnable> postInitTasks = new ArrayList<Runnable>();

    /* The timer constantly ensures the client's polling with the server is active */
    private Timer incomingTimer;

    /* True if the client's message bus has been initialized */
    private boolean initialized = false;

    private Timer heartBeatTimer;
    private long lastTransmit = 0;


    /**
     * Constructor creates sendBuilder for HTTP POST requests, recvBuilder for HTTP GET requests and
     * initializes the message bus.
     */
    public ClientMessageBusImpl() {
        (sendBuilder = new RequestBuilder(
                RequestBuilder.POST,
                URL.encode(SERVICE_ENTRY_POINT)
        )).setHeader("Connection", "Keep-Alive");

        (recvBuilder = new RequestBuilder(
                RequestBuilder.GET,
                URL.encode(SERVICE_ENTRY_POINT)
        )).setHeader("Connection", "Keep-Alive");

        init();
    }

    /**
     * Removes all subscriptions attached to the specified subject
     *
     * @param subject - the subject to have all it's subscriptions removed
     */
    public void unsubscribeAll(String subject) {
        if (subscriptions.containsKey(subject)) {
            for (Object o : subscriptions.get(subject)) {
                _unsubscribe(o);
            }

            fireAllUnSubscribeListeners(subject);
        }
    }

    /**
     * Add a subscription for the specified subject
     *
     * @param subject  - the subject to add a subscription for
     * @param callback - function called when the message is dispatched
     */
    public void subscribe(final String subject, final MessageCallback callback) {
        fireAllSubscribeListeners(subject);

        MessageCallback dispatcher = new MessageCallback() {
            public void callback(Message message) {
                try {
                    callback.callback(message);
                }
                catch (Exception e) {
                    showError("Receiver '" + subject + "' threw an exception", decodeCommandMessage(message), e);
                }
            }
        };

        addSubscription(subject, _subscribe(subject, dispatcher, null));
    }

    /**
     * Fire listeners to notify that a new subscription has been registered on the bus.
     *
     * @param subject - new subscription registered
     */
    private void fireAllSubscribeListeners(String subject) {
        Iterator<SubscribeListener> iter = onSubscribeHooks.iterator();
        SubscriptionEvent evt = new SubscriptionEvent(false, "InBrowser", subject);

        while (iter.hasNext()) {
            iter.next().onSubscribe(evt);
            if (evt.isDisposeListener()) {
                iter.remove();
                evt.setDisposeListener(false);
            }
        }
    }

    /**
     * Fire listeners to notify that a subscription has been unregistered from the bus
     *
     * @param subject - subscription unregistered
     */
    private void fireAllUnSubscribeListeners(String subject) {
        Iterator<UnsubscribeListener> iter = onUnsubscribeHooks.iterator();
        SubscriptionEvent evt = new SubscriptionEvent(false, "InBrowser", subject);

        while (iter.hasNext()) {
            iter.next().onUnsubscribe(evt);
            if (evt.isDisposeListener()) {
                iter.remove();
                evt.setDisposeListener(false);
            }
        }
    }

    private static int conversationCounter = 0;

    /**
     * Have a single two-way conversation
     *
     * @param message  - The message to be sent in the conversation
     * @param callback - The function to be called when the message is received
     */
    public void conversationWith(final Message message, final MessageCallback callback) {
        final String tempSubject = "temp:Conversation:" + (++conversationCounter);

        message.set(ReplyTo, tempSubject);

        subscribe(tempSubject, new MessageCallback() {
            public void callback(Message message) {
                unsubscribeAll(tempSubject);
                callback.callback(message);
            }
        });

        send(message);
    }

    /**
     * Globally send message to all receivers.
     *
     * @param message - The message to be sent.
     */
    public void sendGlobal(Message message) {
        send(message);
    }

    /**
     * Sends the specified message, and notifies the listeners.
     *
     * @param message       - the message to be sent
     * @param fireListeners - true if the appropriate listeners should be fired
     */
    public void send(Message message, boolean fireListeners) {
        // TODO: fire listeners?

        send(message);
    }

    /**
     * Sends the message using it's encoded subject. If the bus has not been initialized, it will be added to
     * <tt>postInitTasks</tt>.
     *
     * @param message
     * @throws RuntimeException - if message does not contain a ToSubject field or if the message's callback throws
     *                          an error.
     */
    public void send(final Message message) {
        try {
            if (message.hasPart(MessageParts.ToSubject)) {
                if (!initialized) {
                    postInitTasks.add(new Runnable() {
                        public void run() {
                            _store(message.getSubject(), message instanceof HasEncoded
                                    ? ((HasEncoded) message).getEncoded() : encodeMap(message.getParts()));
                        }
                    });
                } else {
                    _store(message.getSubject(), message instanceof HasEncoded
                            ? ((HasEncoded) message).getEncoded() : encodeMap(message.getParts()));
                }

            } else {
                throw new RuntimeException("Cannot send message using this method" +
                        " if the message does not contain a ToSubject field.");
            }
        }
        catch (RuntimeException e) {
            if (message.getErrorCallback() != null) {
                if (!message.getErrorCallback().error(message, e)) {
                    return;
                }
            }
            throw e;
        }
    }

    /**
     * Add message to the queue that remotely transmits messages to the server.
     * All messages in the queue are then sent.
     *
     * @param message
     */
    public void enqueueForRemoteTransmit(Message message) {
        outgoingQueue.add(message instanceof HasEncoded ?
                ((HasEncoded) message).getEncoded() : encodeMap(message.getParts()));
        sendAll();
    }

    private void addSubscription(String subject, Object reference) {
        if (!subscriptions.containsKey(subject)) {
            subscriptions.put(subject, new ArrayList<Object>());
        }

        if (registeredInThisSession != null && !registeredInThisSession.containsKey(subject)) {
            registeredInThisSession.put(subject, new HashSet<Object>());
        }

        subscriptions.get(subject).add(reference);
        if (registeredInThisSession != null) registeredInThisSession.get(subject).add(reference);
    }

    /**
     * Checks if subject is already listed in the subscriptions map
     *
     * @param subject - subject to look for
     * @return true if the subject is already subscribed
     */
    public boolean isSubscribed(String subject) {
        return subscriptions.containsKey(subject);
    }

    /**
     * Retrieve all registrations that have occured during the current capture context.
     * <p/>
     * The Map returned has the subject of the registrations as the key, and Sets of registration objects as the
     * value of the Map.
     *
     * @return A map of registrations captured in the current capture context.
     */
    public Map<String, Set<Object>> getCapturedRegistrations() {
        return registeredInThisSession;
    }

    /**
     * Marks the beginning of a new capture context.<p/>  From this point, the message is called forward, all
     * registration events which occur will be captured.
     */
    public void beginCapture() {
        registeredInThisSession = new HashMap<String, Set<Object>>();
    }

    /**
     * End the current capturing context.
     */
    public void endCapture() {
        registeredInThisSession = null;
    }

    /**
     * Unregister all registrations in the specified Map.<p/>  It accepts a Map format returned from
     * {@link #getCapturedRegistrations()}.
     *
     * @param all A map of registrations to deregister.
     */
    public void unregisterAll(Map<String, Set<Object>> all) {
        for (Map.Entry<String, Set<Object>> entry : all.entrySet()) {
            for (Object o : entry.getValue()) {
                subscriptions.get(entry.getKey()).remove(o);
                _unsubscribe(o);
            }

            if (subscriptions.get(entry.getKey()).isEmpty()) {
                fireAllUnSubscribeListeners(entry.getKey());
            }
        }
    }

    private Timer sendTimer;

    /**
     * Appends all messages in the queue to as a JSON-like string, and remotely transmits them all.
     */
    private void sendAll() {
        if (!initialized) {
            return;
        } else if (transmitting) {
            if (sendTimer == null) {
                sendTimer = new Timer() {
                    int timeout = 0;

                    @Override
                    public void run() {
                        if (outgoingQueue.isEmpty()) {
                            cancel();
                        }

                        sendAll();

                        /**
                         * If this fails 20 times then we stop blocking
                         * progress and allow more messages to flow.
                         */
                        if (++timeout > 20) {
                            transmitting = false;
                        }
                    }
                };
                sendTimer.scheduleRepeating(75);
            }

            return;
        } else if (sendTimer != null) {
            sendTimer.cancel();
            sendTimer = null;
        }

        int transmissionSize = outgoingQueue.size();
        StringBuffer outgoing = new StringBuffer();
        for (int i = 0; i < transmissionSize; i++) {
            outgoing.append(outgoingQueue.poll());

            if ((i + 1) < transmissionSize) {
                outgoing.append(JSONUtilCli.MULTI_PAYLOAD_SEPER);
            }
        }

        if (transmissionSize != 0) transmitRemote(outgoing.toString());
    }

    /**
     * Transmits JSON string containing message, using the <tt>sendBuilder</tt>
     *
     * @param message - JSON string representation of message
     */
    private void transmitRemote(String message) {
        if (message == null) return;

        try {
            transmitting = true;

            sendBuilder.sendRequest(message, new RequestCallback() {
                public void onResponseReceived(Request request, Response response) {
                    transmitting = false;

                    /**
                     * If the server bus returned us some client-destined messages
                     * in response to our send, handle them now.
                     */
                    try {
                        procIncomingPayload(response);
                    }
                    catch (Throwable e) {
                        e.printStackTrace();
                        showError("Problem decoding incoming message:", response.getText(), e);
                    }

                    sendAll();
                }

                public void onError(Request request, Throwable exception) {
                    showError("Failed to communicate with remote bus", "", exception);
                    transmitting = false;
                }
            });
        }
        catch (Exception e) {
            transmitting = false;
            e.printStackTrace();
        }

        lastTransmit = System.currentTimeMillis();
    }

    /**
     * Initializes client message bus without a callback function
     */
    public void init() {
        init(null);
    }

    /**
     * Initializes the message bus, by subscribing to the ClientBus (to receive subscription messages) and the
     * ClientErrorBus to dispatch errors when called.
     *
     * @param callback - callback function used for to send the initial message to connect to the queue.
     */
    public void init(final HookCallback callback) {
        final MessageBus self = this;

        subscribe("ClientBus", new MessageCallback() {
            public void callback(Message message) {
                switch (BusCommands.valueOf(message.getCommandType())) {
                    case RemoteSubscribe:
                        subscribe(message.get(String.class, Subject), new MessageCallback() {
                            public void callback(Message message) {
                                enqueueForRemoteTransmit(message);
                            }
                        });
                        break;

                    case RemoteUnsubscribe:
                        unsubscribeAll(message.get(String.class, Subject));
                        break;

                    case FinishStateSync:
                        for (String s : subscriptions.keySet()) {
                            if (s.startsWith("local:")) continue;

                            MessageBuilder.createMessage()
                                    .toSubject("ServerBus")
                                    .command(RemoteSubscribe)
                                    .with(Subject, s)
                                    .noErrorHandling()
                                    .sendNowWith(self);
                        }

                        // Don't use an iterator here -- potential for concurrent
                        // modifications!
                        //noinspection ForLoopReplaceableByForEach
                        for (int i = 0; i < postInitTasks.size(); i++) {
                            postInitTasks.get(i).run();
                        }

                        initialized = true;

                        break;

                    case Disconnect:
                        incomingTimer.cancel();
                        break;
                }
            }
        });

        subscribe("ClientBusErrors", new MessageCallback() {
            public void callback(Message message) {
                showError(message.get(String.class, "ErrorMessage"),
                        message.get(String.class, "AdditionalDetails"), null);
            }
        });

        addSubscribeListener(new SubscribeListener() {
            public void onSubscribe(SubscriptionEvent event) {
                if (event.getSubject().startsWith("local:")) {
                    return;
                }
                create().command(RemoteSubscribe)
                        .toSubject("ServerBus")
                        .set(Subject, event.getSubject())
                        .set(PriorityProcessing, "1")
                        .sendNowWith(self);
            }
        });

        /**
         * ... also send RemoteUnsubscribe signals.
         */

        addUnsubscribeListener(new UnsubscribeListener() {
            public void onUnsubscribe(SubscriptionEvent event) {
                create().command(BusCommands.RemoteUnsubscribe)
                        .toSubject("ServerBus")
                        .set(Subject, event.getSubject())
                        .set(PriorityProcessing, "1")
                        .sendNowWith(self);
            }
        });


        /**
         * Send initial message to connect to the queue, to establish an HTTP session. Otherwise, concurrent
         * requests will result in multiple sessions being created.  Which is bad.  Avoid this at all costs.
         * Please.
         */
        if (!sendInitialMessage(callback)) {
            showError("Could not connect to remote bus", "", null);
        }
    }

    /**
     * Sends the initial message to connect to the queue, to estabish an HTTP session. Otherwise, concurrent
     * requests will result in multiple sessions being created.
     *
     * @param callback - callback function used for initializing the message bus
     * @return true if initial message was sent successfully.
     */
    private boolean sendInitialMessage(final HookCallback callback) {
        try {
            String initialMessage = "{\"CommandType\":\"ConnectToQueue\",\"ToSubject\":\"ServerBus\"," +
                    " \"PriorityProcessing\":\"1\"}";

            sendBuilder.sendRequest(initialMessage, new RequestCallback() {
                public void onResponseReceived(Request request, Response response) {
                    try {
                        procIncomingPayload(response);
                        initializeMessagingBus(callback);
                    }
                    catch (Exception e) {
                        showError("Error attaching to bus", e.getMessage() + "<br/>Message Contents:<br/>"
                                + response.getText(), e);
                    }
                }

                public void onError(Request request, Throwable exception) {
                    System.out.println("ERROR");
                }
            });
        }
        catch (RequestException e) {
            e.printStackTrace();
            return false;
        }
        return true;
    }

    /**
     * Returns true if client message bus is initialized.
     *
     * @return true if client message bus is initialized.
     */
    public boolean isInitialized() {
        return initialized;
    }

    /**
     * Initializes the message bus by setting up the <tt>recvBuilder</tt> to accept responses. Also, initializes the
     * incoming timer to ensure the client's polling with the server is active.
     *
     * @param initCallback - not used
     */
    @SuppressWarnings({"UnusedDeclaration"})
    private void initializeMessagingBus(final HookCallback initCallback) {
        incomingTimer = new Timer() {
            boolean block = false;

            @Override
            public void run() {
                if (block) {
                    scheduleRepeating(25);
                    return;
                }

                block = true;
                try {
                    recvBuilder.sendRequest(null,
                            new RequestCallback() {
                                public void onError(Request request, Throwable throwable) {
                                    block = false;
                                    showError("Communication Error", "None", throwable);
                                    cancel();
                                }

                                public void onResponseReceived(Request request, Response response) {
                                    block = false;

                                    try {
                                        procIncomingPayload(response);
                                        schedule(1);
                                    }
                                    catch (Throwable e) {
                                        showError("Errai MessageBus Disconnected Due to Fatal Error", response.getText(), e);
                                        cancel();
                                    }
                                }
                            }
                    );
                }
                catch (Exception e) {
                    e.printStackTrace();
                }
                block = false;
            }
        };

        final MessageBus bus = this;

        final Timer outerTimer = new Timer() {
            @Override
            public void run() {
                incomingTimer.scheduleRepeating(500);
                ExtensionsLoader loader = GWT.create(ExtensionsLoader.class);
                loader.initExtensions(bus);
            }
        };

        outerTimer.schedule(10);

        heartBeatTimer = new Timer() {
            @Override
            public void run() {
                if (System.currentTimeMillis() - lastTransmit >= HEARTBEAT_DELAY) {
                    enqueueForRemoteTransmit(MessageBuilder.createMessage().toSubject("ServerBus")
                            .command(BusCommands.Heartbeat).noErrorHandling().getMessage());
                    schedule(HEARTBEAT_DELAY);
                } else {
                    long win = System.currentTimeMillis() - lastTransmit;
                    int diff = HEARTBEAT_DELAY - (int) win;
                    schedule(diff);
                }
            }
        };

        heartBeatTimer.schedule(HEARTBEAT_DELAY);
    }

    /**
     * Add runnable tasks to be run after the message bus is initialized
     *
     * @param run a {@link Runnable} task.
     */
    public void addPostInitTask(Runnable run) {
        postInitTasks.add(run);
    }

    /**
     * Do-nothing function, should eventually be able to add a global listener to receive all messages. Though global
     * message dispatches the message to all listeners attached.
     *
     * @param listener - listener to accept all messages dispatched
     */
    public void addGlobalListener(MessageListener listener) {
    }

    /**
     * Adds a subscription listener, so it is possible to add subscriptions to the client.
     *
     * @param listener - subscription listener
     */
    public void addSubscribeListener(SubscribeListener listener) {
        this.onSubscribeHooks.add(listener);
    }

    /**
     * Adds an unsubscription listener, so it is possible for applications to remove subscriptions from the client
     *
     * @param listener - unsubscription listener
     */
    public void addUnsubscribeListener(UnsubscribeListener listener) {
        this.onUnsubscribeHooks.add(listener);
    }

    private native static void _unsubscribe(Object registrationHandle) /*-{
         $wnd.PageBus.unsubscribe(registrationHandle);
     }-*/;

    private native static Object _subscribe(String subject, MessageCallback callback,
                                            Object subscriberData) /*-{
          return $wnd.PageBus.subscribe(subject, null,
                  function (subject, message, subcriberData) {
                     callback.@org.jboss.errai.bus.client.MessageCallback::callback(Lorg/jboss/errai/bus/client/Message;)(@org.jboss.errai.bus.client.json.JSONUtilCli::decodeCommandMessage(Ljava/lang/Object;)(message))
                  },
                  null);
     }-*/;

    public native static void _store(String subject, Object value) /*-{
          $wnd.PageBus.store(subject, value);
     }-*/;

    private static String decodeCommandMessage(Message msg) {
        StringBuffer decode = new StringBuffer(
                "<table><thead style='font-weight:bold;'><tr><td>Field</td><td>Value</td></tr></thead><tbody>");

        for (Map.Entry<String, Object> entry : msg.getParts().entrySet()) {
            decode.append("<tr><td>").append(entry.getKey()).append("</td><td>").append(entry.getValue()).append("</td></tr>");
        }

        return decode.append("</tbody></table>").toString();
    }

    class BusErrorDialog extends DialogBox {
        ScrollPanel scrollPanel;
        VerticalPanel contentPanel = new VerticalPanel();

        public BusErrorDialog() {
            setText("Message Bus Error");

            VerticalPanel panel = new VerticalPanel();
            Button closeButton = new Button("Dismiss");
            closeButton.addClickHandler(new ClickHandler() {
                public void onClick(ClickEvent event) {
                    errorDialog.hide();
                }
            });

            panel.add(closeButton);
            panel.setCellHorizontalAlignment(closeButton, HasHorizontalAlignment.ALIGN_RIGHT);

            Style s = panel.getElement().getStyle();

            s.setProperty("border", "1px");
            s.setProperty("borderStyle", "solid");
            s.setProperty("borderColor", "black");
            s.setProperty("backgroundColor", "lightgrey");


            scrollPanel = new ScrollPanel();
            scrollPanel.setHeight("500px");
            scrollPanel.setAlwaysShowScrollBars(true);
            panel.add(scrollPanel);
            scrollPanel.add(contentPanel);

            add(panel);
        }

        public void addError(String message, String additionalDetails, Throwable e) {
            contentPanel.add(new HTML("<strong style='background:red;color:white;'>" + message + "</strong>"));

            StringBuffer buildTrace = new StringBuffer("<tt style=\"font-size:11px;\"><pre>");
            if (e != null) {
                buildTrace.append(e.getClass().getName()).append(": ").append(e.getMessage()).append("<br/>");
                for (StackTraceElement ste : e.getStackTrace()) {
                    buildTrace.append("  ").append(ste.toString()).append("<br/>");
                }
            }
            buildTrace.append("</pre>");

            contentPanel.add(new HTML(buildTrace.toString() + "<br/><strong>Additional Details:</strong>" + additionalDetails + "</tt>"));

            if (!isShowing()) {
                show();
                center();
            }
        }
    }

    BusErrorDialog errorDialog;

    private void showError(String message, String additionalDetails, Throwable e) {
        if (errorDialog == null) {
            errorDialog = new BusErrorDialog();
        }
        errorDialog.addError(message, additionalDetails, e);
    }

    /**
     * Process the incoming payload and push all the incoming messages onto the bus.
     *
     * @param response
     * @throws Exception
     */
    private void procIncomingPayload(Response response) throws Exception {
        try {
            for (MarshalledMessage m : decodePayload(response.getText())) {
                _store(m.getSubject(), m.getMessage());
            }
        }
        catch (RuntimeException e) {
            showError("Error delivering message into bus", response.getText(), e);
        }
    }

    private final MessageProvider provider = new MessageProvider() {
        {
            MessageBuilder.setProvider(this);
        }
        @Override
        public Message get() {
            return new JSONMessage();
        }
    };
    
    @Override
    public MessageProvider getMessageProvider() {
        return provider;
    }
}
