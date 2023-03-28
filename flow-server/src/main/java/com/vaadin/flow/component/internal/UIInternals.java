/*
 * Copyright 2000-2023 Vaadin Ltd.
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
package com.vaadin.flow.component.internal;

import java.io.Serializable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.ComponentUtil;
import com.vaadin.flow.component.HasElement;
import com.vaadin.flow.component.HeartbeatEvent;
import com.vaadin.flow.component.HeartbeatListener;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.dependency.CssImport;
import com.vaadin.flow.component.dependency.JavaScript;
import com.vaadin.flow.component.dependency.JsModule;
import com.vaadin.flow.component.dependency.StyleSheet;
import com.vaadin.flow.component.internal.ComponentMetaData.DependencyInfo;
import com.vaadin.flow.component.page.ExtendedClientDetails;
import com.vaadin.flow.component.page.Page;
import com.vaadin.flow.dom.ElementUtil;
import com.vaadin.flow.dom.impl.BasicElementStateProvider;
import com.vaadin.flow.internal.ConstantPool;
import com.vaadin.flow.internal.JsonCodec;
import com.vaadin.flow.internal.StateTree;
import com.vaadin.flow.internal.UrlUtil;
import com.vaadin.flow.internal.nodefeature.LoadingIndicatorConfigurationMap;
import com.vaadin.flow.internal.nodefeature.NodeFeature;
import com.vaadin.flow.internal.nodefeature.PollConfigurationMap;
import com.vaadin.flow.internal.nodefeature.PushConfigurationMap;
import com.vaadin.flow.internal.nodefeature.ReconnectDialogConfigurationMap;
import com.vaadin.flow.router.AfterNavigationListener;
import com.vaadin.flow.router.BeforeEnterListener;
import com.vaadin.flow.router.BeforeLeaveEvent.ContinueNavigationAction;
import com.vaadin.flow.router.BeforeLeaveListener;
import com.vaadin.flow.router.ListenerPriority;
import com.vaadin.flow.router.Location;
import com.vaadin.flow.router.Router;
import com.vaadin.flow.router.RouterLayout;
import com.vaadin.flow.router.internal.AfterNavigationHandler;
import com.vaadin.flow.router.internal.BeforeEnterHandler;
import com.vaadin.flow.router.internal.BeforeLeaveHandler;
import com.vaadin.flow.server.VaadinContext;
import com.vaadin.flow.server.VaadinService;
import com.vaadin.flow.server.VaadinSession;
import com.vaadin.flow.server.communication.PushConnection;
import com.vaadin.flow.server.frontend.FallbackChunk;
import com.vaadin.flow.server.frontend.FallbackChunk.CssImportData;
import com.vaadin.flow.shared.Registration;
import com.vaadin.flow.shared.communication.PushMode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Holds UI-specific methods and data which are intended for internal use by the
 * framework.
 *
 * <p>
 * For internal use only. May be renamed or removed in a future release.
 *
 * @author Vaadin Ltd
 * @since 1.0
 */
public class UIInternals implements Serializable {

    /**
     * A {@link Page#executeJs(String, Serializable...)} invocation that has not
     * yet been sent to the client.
     */
    public static class JavaScriptInvocation implements Serializable {
        private final String expression;
        private final List<Serializable> parameters = new ArrayList<>();

        /**
         * Creates a new invocation.
         *
         * @param expression
         *            the expression to invoke
         * @param parameters
         *            a list of parameters to use when invoking the script
         */
        public JavaScriptInvocation(String expression,
                Serializable... parameters) {
            /*
             * To ensure attached elements are actually attached, the parameters
             * won't be serialized until the phase the UIDL message is created.
             * To give the user immediate feedback if using a parameter type
             * that can't be serialized, we do a dry run at this point.
             */
            for (Object argument : parameters) {
                // Throws IAE for unsupported types
                JsonCodec.encodeWithTypeInfo(argument);
            }

            this.expression = expression;
            Collections.addAll(this.parameters, parameters);
        }

        /**
         * Gets the JavaScript expression to invoke.
         *
         * @return the JavaScript expression
         */
        public String getExpression() {
            return expression;
        }

        /**
         * Gets the parameters to use when invoking the script.
         *
         * @return a list of parameters to use
         */
        public List<Object> getParameters() {
            return Collections.unmodifiableList(parameters);
        }
    }

    /**
     * Tracks which message from the client should come next. First message from
     * the client has id 0.
     */
    private int lastProcessedClientToServerId = -1;

    private int serverSyncId = 0;

    private final StateTree stateTree;

    private PushConnection pushConnection = null;

    /**
     * Timestamp for keeping track of the last heartbeat of the related UI.
     * Updated to the current time whenever the application receives a heartbeat
     * or UIDL request from the client for the related UI.
     */
    private long lastHeartbeatTimestamp = System.currentTimeMillis();

    private List<PendingJavaScriptInvocation> pendingJsInvocations = new ArrayList<>();

    /**
     * The related UI.
     */
    private final UI ui;

    private final UIInternalUpdater internalsHandler;

    private String title;

    private String appShellTitle;

    private PendingJavaScriptInvocation pendingTitleUpdateCanceler;

    private Location viewLocation = new Location("");
    private ArrayList<HasElement> routerTargetChain = new ArrayList<>();

    private HashMap<Class<?>, List<?>> listeners = new HashMap<>();

    private Location lastHandledNavigation = null;

    private ContinueNavigationAction continueNavigationAction = null;

    /**
     * The Vaadin session to which the related UI belongs.
     */
    private volatile VaadinSession session;

    private final DependencyList dependencyList = new DependencyList();

    private final ConstantPool constantPool = new ConstantPool();

    private byte[] lastProcessedMessageHash = null;

    private String contextRootRelativePath;

    private String appId;

    private Component activeDragSourceComponent;

    private ExtendedClientDetails extendedClientDetails = null;

    private boolean isFallbackChunkLoaded;

    private ArrayDeque<Component> modalComponentStack;

    /**
     * Creates a new instance for the given UI.
     *
     * @param ui
     *            the UI to use
     */
    public UIInternals(UI ui) {
        this(ui, new UIInternalUpdater() {
        });
    }

    /**
     * Creates a new instance for the given UI.
     *
     * @param ui
     *            the UI to use
     * @param internalsHandler
     *            an implementation of {@link UIInternalUpdater}
     */
    public UIInternals(UI ui, UIInternalUpdater internalsHandler) {
        this.internalsHandler = internalsHandler;
        this.ui = ui;
        stateTree = new StateTree(this, getRootNodeFeatures());
    }

    /**
     * Gets the state tree of the related UI.
     *
     * @return the state tree
     */
    public StateTree getStateTree() {
        return stateTree;
    }

    /**
     * Gets the last processed server message id.
     * <p>
     * Used internally for communication tracking.
     *
     * @return lastProcessedServerMessageId the id of the last processed server
     *         message
     */
    public int getLastProcessedClientToServerId() {
        return lastProcessedClientToServerId;
    }

    /**
     * Gets the hash of the last processed message from the client.
     * <p>
     * The hash is set through
     * {@link #setLastProcessedClientToServerId(int, byte[])}.
     * <p>
     * Used internally for communication tracking.
     *
     * @return the hash as a byte array, or <code>null</code> if no hash has
     *         been set
     */
    public byte[] getLastProcessedMessageHash() {
        return lastProcessedMessageHash;
    }

    /**
     * Sets the last processed server message id.
     * <p>
     * Used internally for communication tracking.
     *
     * @param lastProcessedClientToServerId
     *            the id of the last processed server message
     * @param lastProcessedMessageHash
     *            the hash of the message
     */
    public void setLastProcessedClientToServerId(
            int lastProcessedClientToServerId,
            byte[] lastProcessedMessageHash) {
        this.lastProcessedClientToServerId = lastProcessedClientToServerId;
        this.lastProcessedMessageHash = lastProcessedMessageHash;
    }

    /**
     * Gets the server sync id.
     * <p>
     * The sync id is incremented by one whenever a new response is written.
     * This id is then sent over to the client. The client then adds the most
     * recent sync id to each communication packet it sends back to the server.
     * This way, the server knows at what state the client is when the packet is
     * sent. If the state has changed on the server side since that, the server
     * can try to adjust the way it handles the actions from the client side.
     * <p>
     * The sync id value <code>-1</code> is ignored to facilitate testing with
     * pre-recorded requests.
     *
     * @return the server sync id
     */
    public int getServerSyncId() {
        return serverSyncId;
    }

    /**
     * Increments the server sync id.
     * <p>
     * This should only be called by whoever sends a message to the client,
     * after the message has been sent.
     */
    public void incrementServerId() {
        serverSyncId++;
        if (getLogger().isTraceEnabled()) {
            getLogger().trace("Increment syncId:\n{}", Arrays
                    .stream(Thread.currentThread().getStackTrace()).skip(1)
                    .map(String::valueOf)
                    .collect(Collectors.joining(System.lineSeparator())));
        }
    }

    /**
     * Returns the timestamp of the last received heartbeat for the related UI.
     * <p>
     * This method is not intended to be overridden. If it is overridden, care
     * should be taken since this method might be called in situations where
     * {@link UI#getCurrent()} does not return the UI.
     *
     * @return The time the last heartbeat request occurred, in milliseconds
     *         since the epoch.
     * @see VaadinService#closeInactiveUIs(VaadinSession)
     */
    public long getLastHeartbeatTimestamp() {
        return lastHeartbeatTimestamp;
    }

    /**
     * Sets the last heartbeat request timestamp for the related UI. Called by
     * the framework whenever the application receives a valid heartbeat request
     * for the UI.
     * <p>
     * This method is not intended to be overridden. If it is overridden, care
     * should be taken since this method might be called in situations where
     * {@link UI#getCurrent()} does not return the UI.
     *
     * @param lastHeartbeat
     *            The time the last heartbeat request occurred, in milliseconds
     *            since the epoch.
     */
    public void setLastHeartbeatTimestamp(long lastHeartbeat) {
        lastHeartbeatTimestamp = lastHeartbeat;
        HeartbeatEvent heartbeatEvent = new HeartbeatEvent(ui, lastHeartbeat);
        getListeners(HeartbeatListener.class)
                .forEach(listener -> listener.heartbeat(heartbeatEvent));
    }

    @SuppressWarnings("unchecked")
    private static Class<? extends NodeFeature>[] getRootNodeFeatures() {
        // Start with all element features
        List<Class<? extends NodeFeature>> features = new ArrayList<>(
                BasicElementStateProvider.getFeatures());

        // Then add our own custom features
        features.add(PushConfigurationMap.class);
        features.add(PollConfigurationMap.class);
        features.add(ReconnectDialogConfigurationMap.class);
        features.add(LoadingIndicatorConfigurationMap.class);

        // And return them all
        assert features.size() == new HashSet<>(features).size()
                : "There are duplicates";
        return (Class<? extends NodeFeature>[]) features
                .toArray(new Class<?>[0]);
    }

    private static String getSessionDetails(VaadinSession session) {
        if (session == null) {
            return null;
        } else {
            return session + " for " + session.getService().getServiceName();
        }
    }

    /**
     * Sets the session to which the related UI is assigned.
     * <p>
     * This method is for internal use by the framework. To explicitly close a
     * UI, see {@link UI#close()}.
     *
     * @param session
     *            the session to set
     * @throws IllegalStateException
     *             if the session has already been set
     * @see #getSession()
     */
    public void setSession(VaadinSession session) {
        if (session == null && this.session == null) {
            throw new IllegalStateException(
                    "Session should never be set to null when UI.session is already null");
        } else if (session != null && this.session != null) {
            throw new IllegalStateException(
                    "Session has already been set. Old session: "
                            + getSessionDetails(this.session)
                            + ". New session: " + getSessionDetails(session)
                            + ".");
        } else {
            if (session == null) {
                ui.getElement().getNode().setParent(null);
                // Disable push when the UI is detached. Otherwise the
                // push connection and possibly VaadinSession will live on.
                ui.getPushConfiguration().setPushMode(PushMode.DISABLED);
                setPushConnection(null);
            }
            this.session = session;
        }

        if (session != null) {
            ComponentUtil.onComponentAttach(ui, true);
        }
    }

    /**
     * Returns the internal push connection object used by the related UI. This
     * method should only be called by the framework.
     * <p>
     * This method is not intended to be overridden. If it is overridden, care
     * should be taken since this method might be called in situations where
     * {@link UI#getCurrent()} does not return the UI.
     *
     * @return the push connection used by the UI, or {@code null} if push is
     *         not available.
     */
    public PushConnection getPushConnection() {
        assert !(ui.getPushConfiguration().getPushMode().isEnabled()
                && pushConnection == null);
        return pushConnection;
    }

    /**
     * Sets the internal push connection object used by the related UI. This
     * method should only be called by the framework.
     * <p>
     * The {@code pushConnection} argument must be non-null if and only if
     * {@code getPushConfiguration().getPushMode().isEnabled()}.
     *
     * @param pushConnection
     *            the push connection to use for the UI
     */
    public void setPushConnection(PushConnection pushConnection) {
        // If pushMode is disabled then there should never be a pushConnection;
        // if enabled there should always be
        assert (pushConnection == null)
                ^ ui.getPushConfiguration().getPushMode().isEnabled();

        if (pushConnection == this.pushConnection) {
            return;
        }

        if (this.pushConnection != null && this.pushConnection.isConnected()) {
            this.pushConnection.disconnect();
        }

        this.pushConnection = pushConnection;
    }

    /**
     * Add a listener that will be informed when a new set of components are
     * going to be attached.
     *
     * @param listener
     *            the before enter listener
     * @return handler to remove the event listener
     */
    public Registration addBeforeEnterListener(BeforeEnterListener listener) {
        return addListener(BeforeEnterHandler.class, listener);
    }

    /**
     * Add a listener that will be informed when old components are detached.
     *
     * @param listener
     *            the before leave listener
     * @return handler to remove the event listener
     */
    public Registration addBeforeLeaveListener(BeforeLeaveListener listener) {
        return addListener(BeforeLeaveHandler.class, listener);
    }

    /**
     * Add a listener that will be informed when new components have been
     * attached and all navigation tasks have resolved.
     *
     * @param listener
     *            the after navigation listener
     * @return handler to remove the event listener
     */
    public Registration addAfterNavigationListener(
            AfterNavigationListener listener) {
        return addListener(AfterNavigationHandler.class, listener);
    }

    public Registration addHeartbeatListener(HeartbeatListener listener) {
        return addListener(HeartbeatListener.class, listener);
    }

    private <E> Registration addListener(Class<E> handler, E listener) {
        session.checkHasLock();
        List<E> list = (List<E>) listeners.computeIfAbsent(handler,
                key -> new ArrayList<>());
        list.add(listener);

        list.sort((o1, o2) -> {
            Class<?> o1Class = o1.getClass();
            Class<?> o2Class = o2.getClass();

            final ListenerPriority listenerPriority1 = o1Class
                    .getAnnotation(ListenerPriority.class);
            final ListenerPriority listenerPriority2 = o2Class
                    .getAnnotation(ListenerPriority.class);

            final int priority1 = listenerPriority1 != null
                    ? listenerPriority1.value()
                    : 0;
            final int priority2 = listenerPriority2 != null
                    ? listenerPriority2.value()
                    : 0;

            // we want to have a descending order
            return Integer.compare(priority2, priority1);
        });

        return () -> list.remove(listener);
    }

    /**
     * Get all registered listeners for given navigation handler type.
     *
     * @param handler
     *            handler to get listeners for
     * @param <E>
     *            the handler type
     * @return unmodifiable list of registered listeners for navigation handler
     */
    public <E> List<E> getListeners(Class<E> handler) {
        List<E> registeredListeners = (List<E>) listeners
                .computeIfAbsent(handler, key -> new ArrayList<>());

        return Collections
                .unmodifiableList(new ArrayList<>(registeredListeners));
    }

    /**
     * Adds a JavaScript invocation to be sent to the client.
     *
     * @param invocation
     *            the invocation to add
     */
    public void addJavaScriptInvocation(
            PendingJavaScriptInvocation invocation) {
        session.checkHasLock();
        pendingJsInvocations.add(invocation);
    }

    /**
     * Gets all the pending JavaScript invocations and clears the queue.
     *
     * @return a list of pending JavaScript invocations
     */
    public List<PendingJavaScriptInvocation> dumpPendingJavaScriptInvocations() {
        pendingTitleUpdateCanceler = null;

        if (pendingJsInvocations.isEmpty()) {
            return Collections.emptyList();
        }

        List<PendingJavaScriptInvocation> currentList = getPendingJavaScriptInvocations()
                .peek(PendingJavaScriptInvocation::setSentToBrowser)
                .collect(Collectors.toList());

        pendingJsInvocations = new ArrayList<>();

        return currentList;
    }

    /**
     * Gets the pending javascript invocations added with
     * {@link #addJavaScriptInvocation(PendingJavaScriptInvocation)} after last
     * {@link #dumpPendingJavaScriptInvocations()}.
     *
     * @return the pending javascript invocations, never <code>null</code>
     */
    // Non-private for testing purposes
    Stream<PendingJavaScriptInvocation> getPendingJavaScriptInvocations() {
        return pendingJsInvocations.stream()
                .filter(invocation -> !invocation.isCanceled());
    }

    /**
     * Records the page title set with {@link Page#setTitle(String)}.
     * <p>
     * You should not set the page title for the browser with this method, use
     * {@link Page#setTitle(String)} instead.
     *
     * @param title
     *            the title to set
     */
    public void setTitle(String title) {
        assert title != null;
        JavaScriptInvocation invocation = new JavaScriptInvocation(
                "document.title = $0", title);

        pendingTitleUpdateCanceler = new PendingJavaScriptInvocation(
                getStateTree().getRootNode(), invocation);
        addJavaScriptInvocation(pendingTitleUpdateCanceler);

        this.title = title;
    }

    /**
     * Records the text content of the title tag in the application shell.
     * <p>
     * <b>NOTE</b> Intended for internal use, you should not call this method.
     *
     * @param appShellTitle
     *            the appShellTitle to set
     */
    public void setAppShellTitle(String appShellTitle) {
        this.appShellTitle = appShellTitle;
    }

    /**
     * Gets the page title recorded with {@link Page#setTitle(String)}.
     * <p>
     * <b>NOTE</b> this might not be up to date with the actual title set since
     * it is not updated from the browser and the update might have been
     * canceled before it has been sent to the browser with
     * {@link #cancelPendingTitleUpdate()}.
     *
     * @return the page title
     */
    public String getTitle() {
        return title;
    }

    /**
     * Gets the stored app shell title.
     * <p>
     * <b>NOTE</b> Intended for internal use, you should not call this method.
     *
     * @return the app shell title
     */
    public String getAppShellTitle() {
        return appShellTitle;
    }

    /**
     * Cancels any pending page title update set via {@link #setTitle(String)}.
     *
     * @return <code>true</code> if pending title update was cancelled,
     *         <code>false</code> if not
     */
    public boolean cancelPendingTitleUpdate() {
        if (pendingTitleUpdateCanceler == null) {
            return false;
        }

        boolean result = pendingTitleUpdateCanceler.cancelExecution();
        pendingTitleUpdateCanceler = null;
        return result;
    }

    /**
     * Shows a route target in the related UI. This method is intended for
     * framework use only. Use {@link UI#navigate(String)} to change the route
     * target that is shown in a UI.
     *
     * @param viewLocation
     *            the location of the route target relative to the servlet
     *            serving the UI, not <code>null</code>
     * @param target
     *            the component to show, not <code>null</code>
     * @param layouts
     *            the parent layouts
     */
    public void showRouteTarget(Location viewLocation, Component target,
            List<RouterLayout> layouts) {
        assert target != null;
        assert viewLocation != null;

        HasElement oldRoot = null;
        if (!routerTargetChain.isEmpty()) {
            oldRoot = routerTargetChain.get(routerTargetChain.size() - 1);
        }

        this.viewLocation = viewLocation;

        // Assemble previous parent-child relationships to enable detecting
        // changes
        Map<RouterLayout, HasElement> oldChildren = new IdentityHashMap<>();
        for (int i = 0; i < routerTargetChain.size() - 1; i++) {
            HasElement child = routerTargetChain.get(i);
            RouterLayout parent = (RouterLayout) routerTargetChain.get(i + 1);

            oldChildren.put(parent, child);
        }

        routerTargetChain = new ArrayList<>();
        routerTargetChain.add(target);

        if (layouts != null) {
            routerTargetChain.addAll(layouts);
        }

        // If the old and the new router target chains are not intersect,
        // meaning that the new chain doesn't contain the root router
        // layout node of the old chain, this aims to recursively remove
        // content of the all nested router layouts of the given old content
        // to be detached. This is needed to let Dependency Injection
        // frameworks to re-create managed components with no
        // duplicates/leftovers.
        if (oldRoot != null && !routerTargetChain.contains(oldRoot)) {
            oldChildren.forEach(RouterLayout::removeRouterLayoutContent);
        }

        // Ensure the entire chain is connected
        HasElement previous = null;
        for (HasElement current : routerTargetChain) {
            if (previous != null || oldChildren.containsKey(current)) {
                /*
                 * Either we're beyond the initial leaf entry, or then it's now
                 * the leaf but was previously a non-leaf.
                 *
                 * In either case, we should update the contents of the current
                 * entry based on its current position in the chain.
                 */
                assert current instanceof RouterLayout
                        : "All parts of the chain except the first must implement "
                                + RouterLayout.class.getSimpleName();

                HasElement oldContent = oldChildren.get(current);
                HasElement newContent = previous;

                if (oldContent != newContent) {
                    RouterLayout layout = (RouterLayout) current;
                    removeChildrenContentFromRouterLayout(layout, oldChildren);
                    layout.showRouterLayoutContent(newContent);
                }
            }
            previous = current;
        }

        // Final "previous" from the chain is the root component
        HasElement root = previous;

        if (root == null) {
            throw new IllegalArgumentException(
                    "Root can't be null here since we know there's at least one item in the chain");
        }

        internalsHandler.updateRoot(ui, oldRoot, root);
    }

    /**
     * Move all the children of the other UI to this current UI.
     *
     * @param otherUI
     *            the other UI to transfer content from.
     */
    public void moveElementsFrom(UI otherUI) {
        internalsHandler.moveToNewUI(otherUI, ui);
    }

    /**
     * Gets the currently active router target and parent layouts.
     *
     * @return a list of active router target and parent layout instances,
     *         starting from the innermost part
     */
    public List<HasElement> getActiveRouterTargetsChain() {
        return Collections.unmodifiableList(routerTargetChain);
    }

    /**
     * Gets the location of the currently shown view. The location is relative
     * the servlet mapping used for serving the related UI.
     *
     * @return the view location, not <code>null</code>
     */
    public Location getActiveViewLocation() {
        return viewLocation;
    }

    /**
     * Gets the VaadinSession to which the related UI is attached.
     *
     * <p>
     * The method will return {@code null} if the UI is not currently attached
     * to a VaadinSession.
     * </p>
     *
     * @return the VaadinSession to which the related UI is attached
     */
    public VaadinSession getSession() {
        return session;
    }

    private static Logger getLogger() {
        return LoggerFactory.getLogger(UIInternals.class.getName());
    }

    /**
     * Returns the helper which handles loading of dependencies (css, js).
     *
     * @return the dependency list helper
     */
    public DependencyList getDependencyList() {
        return dependencyList;
    }

    /**
     * Adds the dependencies defined using {@link StyleSheet} or
     * {@link JavaScript} on the given Component class.
     *
     * @param componentClass
     *            the component class to read annotations from
     */
    public void addComponentDependencies(
            Class<? extends Component> componentClass) {
        Page page = ui.getPage();
        DependencyInfo dependencies = ComponentUtil
                .getDependencies(session.getService(), componentClass);
        // In npm mode, add external JavaScripts directly to the page.
        addExternalDependencies(dependencies);
        addFallbackDependencies(dependencies);

        dependencies.getStyleSheets().forEach(styleSheet -> page
                .addStyleSheet(styleSheet.value(), styleSheet.loadMode()));
    }

    private void addFallbackDependencies(DependencyInfo dependency) {
        if (isFallbackChunkLoaded) {
            return;
        }
        VaadinContext context = ui.getSession().getService().getContext();
        FallbackChunk chunk = context.getAttribute(FallbackChunk.class);
        if (chunk == null) {
            if (getLogger().isDebugEnabled()) {
                getLogger().debug(
                        "Fallback chunk is not available, skipping fallback dependencies load");
            }
            return;
        }

        Set<String> modules = chunk.getModules();
        Set<CssImportData> cssImportsData = chunk.getCssImports();
        if (modules.isEmpty() && cssImportsData.isEmpty()) {
            getLogger().debug(
                    "Fallback chunk is empty, skipping fallback dependencies load");
            return;
        }

        List<CssImport> cssImports = dependency.getCssImports();
        List<JavaScript> javaScripts = dependency.getJavaScripts();
        List<JsModule> jsModules = dependency.getJsModules();

        if (jsModules.stream().map(JsModule::value)
                .anyMatch(modules::contains)) {
            loadFallbackChunk();
            return;
        }

        if (javaScripts.stream().map(JavaScript::value)
                .anyMatch(modules::contains)) {
            loadFallbackChunk();
            return;
        }

        if (cssImports.stream().map(this::buildData)
                .anyMatch(cssImportsData::contains)) {
            loadFallbackChunk();
            return;
        }
    }

    private CssImportData buildData(CssImport imprt) {
        Function<String, String> converter = str -> str.isEmpty() ? null : str;
        return new CssImportData(converter.apply(imprt.value()),
                converter.apply(imprt.id()), converter.apply(imprt.include()),
                converter.apply(imprt.themeFor()));
    }

    private void loadFallbackChunk() {
        if (isFallbackChunkLoaded) {
            return;
        }
        ui.getPage().addDynamicImport(
                "var fallback = window.Vaadin.Flow.fallbacks['" + getAppId()
                        + "']; if (fallback.loadFallback) { return fallback.loadFallback(); } "
                        + "else { return Promise.resolve(0); }");
        isFallbackChunkLoaded = true;
    }

    private void addExternalDependencies(DependencyInfo dependency) {
        Page page = ui.getPage();
        dependency.getJavaScripts().stream()
                .filter(js -> UrlUtil.isExternal(js.value()))
                .forEach(js -> page.addJavaScript(js.value(), js.loadMode()));
        dependency.getJsModules().stream()
                .filter(js -> UrlUtil.isExternal(js.value()))
                .forEach(js -> page.addJsModule(js.value()));
    }

    /**
     * Gets the constant pool that is used for keeping track of constants shared
     * with the client for this UI.
     *
     * @return the constant pool to use, not <code>null</code>
     */
    public ConstantPool getConstantPool() {
        return constantPool;
    }

    /**
     * Get the latest handled location or empty optional if no active
     * navigation.
     *
     * @return location if navigated during active navigation or {@code null}
     */
    public Location getLastHandledLocation() {
        return lastHandledNavigation;
    }

    /**
     * Set the latest navigation location for active navigation.
     *
     * @param location
     *            last location navigated to
     */
    public void setLastHandledNavigation(Location location) {
        lastHandledNavigation = location;
    }

    /**
     * Check if we have already started navigation to some location on this
     * roundtrip.
     *
     * @return true if the last navigation location {@code !=} null
     */
    public boolean hasLastHandledLocation() {
        return lastHandledNavigation != null;
    }

    /**
     * Clear latest handled navigation location.
     */
    public void clearLastHandledNavigation() {
        setLastHandledNavigation(null);
    }

    /**
     * Get stored {@link ContinueNavigationAction} if any.
     *
     * @return continue navigation action object
     */
    public ContinueNavigationAction getContinueNavigationAction() {
        return continueNavigationAction;
    }

    /**
     * Set a {@link ContinueNavigationAction} or null to clear existing action.
     *
     * @param continueNavigationAction
     *            continue navigation action to store or null
     */
    public void setContinueNavigationAction(
            ContinueNavigationAction continueNavigationAction) {
        this.continueNavigationAction = continueNavigationAction;
    }

    /**
     * Sets the application id tied with this UI. Different applications in the
     * same page have different unique ids.
     *
     * @param appId
     *            the id of the application tied with this UI
     */
    public void setAppId(String appId) {
        this.appId = appId;
    }

    /**
     * Gets the application id tied with this UI. Different applications in the
     * same page have different unique ids.
     *
     * @return the id of the application tied with this UI
     */
    public String getAppId() {
        return appId;
    }

    /**
     * Gets the router used for navigating in this UI, if the router was active
     * when this UI was initialized.
     *
     * @return the router used for this UI, or <code>null</code> if there is no
     *         router or the UI doesn't support navigation.
     */
    public Router getRouter() {
        return ui.isNavigationSupported()
                ? getSession().getService().getRouter()
                : null;
    }

    /**
     * Checks if there are changes waiting to be sent to the client side.
     *
     * @return <code>true</code> if there are pending changes,
     *         <code>false</code> otherwise
     */
    public boolean isDirty() {
        return getStateTree().isDirty()
                || getPendingJavaScriptInvocations().count() != 0;
    }

    /**
     * Sets the relative path from the UI (servlet) path to the context root.
     *
     * @param contextRootRelativePath
     *            the relative path from servlet to context root
     */
    public void setContextRoot(String contextRootRelativePath) {
        this.contextRootRelativePath = contextRootRelativePath;
    }

    /**
     * Gets the relative path from the UI (servlet) path to the context root.
     *
     * @return the relative path from servlet to context root
     */
    public String getContextRootRelativePath() {
        return contextRootRelativePath;
    }

    /**
     * Sets the drag source of an active HTML5 drag event.
     *
     * @param activeDragSourceComponent
     *            the drag source component
     * @since 2.0
     */
    public void setActiveDragSourceComponent(
            Component activeDragSourceComponent) {
        this.activeDragSourceComponent = activeDragSourceComponent;
    }

    /**
     * Gets the drag source of an active HTML5 drag event.
     *
     * @return Extension of the drag source component if the drag event is
     *         active and originated from this UI, {@literal null} otherwise.
     * @since 2.0
     */
    public Component getActiveDragSourceComponent() {
        return activeDragSourceComponent;
    }

    /**
     * Gets the UI that this instance belongs to.
     *
     * @return the UI instance.
     */
    public UI getUI() {
        return ui;
    }

    /**
     * The extended client details, if obtained, are cached in this field.
     *
     * @return the extended client details, or {@literal null} if not yet
     *         received.
     */
    public ExtendedClientDetails getExtendedClientDetails() {
        return extendedClientDetails;
    }

    /**
     * Updates the extended client details.
     *
     * @param details
     *            the updated extended client details.
     */
    public void setExtendedClientDetails(ExtendedClientDetails details) {
        this.extendedClientDetails = details;
    }

    /**
     * Check if we have a modal component defined for the UI.
     *
     * @return {@code true} if modal component is defined
     */
    public boolean hasModalComponent() {
        return modalComponentStack != null && !modalComponentStack.isEmpty();
    }

    /**
     * Get the active modal component if modal components set.
     *
     * @return the current active modal component
     */
    public Component getActiveModalComponent() {
        if (hasModalComponent()) {
            return modalComponentStack.peek();
        }
        return null;
    }

    /**
     * Makes an existing child component modal. This will make the UI and the
     * other components inside it inert - they will not react to any user
     * interaction until the modal component is removed.
     * <p>
     * In case there was an existing modal component in the UI already, that is
     * made inert until the given new component is removed. The UI is no longer
     * inert after all modal components have been removed from it. Inert state
     * is updated automatically when a component is removed from the UI, no need
     * to call anything. Moving an inert component will remove inert status.
     *
     * @param child
     *            the child component to toggle modal
     */
    public void setChildModal(Component child) {
        if (modalComponentStack == null) {
            modalComponentStack = new ArrayDeque<>();
        } else if (isTopMostModal(child)) {
            return;
        }
        ElementUtil.setIgnoreParentInert(child.getElement(), true);

        if (modalComponentStack.isEmpty()) {
            ElementUtil.setInert(ui.getElement(), true);
        } else {
            // disable previous top most modal component
            ElementUtil.setIgnoreParentInert(
                    modalComponentStack.peek().getElement(), false);
        }

        final boolean needsListener = !modalComponentStack.remove(child);
        modalComponentStack.push(child);

        if (needsListener) {
            /*
             * Handle removal automatically on element level always due to
             * possible component.getElement().removeFromParent() usage.
             */
            AtomicReference<Registration> registrationCombination = new AtomicReference<>();
            final Registration componentRemoval = () -> setChildModeless(child);
            final Registration listenerRegistration = child.getElement()
                    .addDetachListener(
                            event -> registrationCombination.get().remove());
            registrationCombination.set(Registration.combine(componentRemoval,
                    listenerRegistration));
        }
    }

    /**
     * Sets the given child modeless. The inert state of the UI and other child
     * components is updated. This method is called automatically when a modal
     * child component is removed from the UI.
     *
     * @param child
     *            the child component to make modeless
     */
    public void setChildModeless(Component child) {
        if (modalComponentStack == null) {
            return;
        }
        boolean isTopmostModal = isTopMostModal(child);
        if (modalComponentStack.remove(child)) {
            if (isTopmostModal) {
                // reset ignoring inert
                ElementUtil.setIgnoreParentInert(child.getElement(), false);
                if (modalComponentStack.isEmpty()) { // make UI active
                    ElementUtil.setInert(ui.getElement(), false);
                } else { // make top most modal component ignore inert
                    ElementUtil.setIgnoreParentInert(
                            modalComponentStack.peek().getElement(), true);
                }
            }
        }
    }

    private boolean isTopMostModal(Component child) {
        // null has been checked in calling code before this
        return !modalComponentStack.isEmpty()
                && modalComponentStack.peek() == child;
    }

    private void removeChildrenContentFromRouterLayout(
            final RouterLayout targetRouterLayout,
            final Map<RouterLayout, HasElement> oldChildren) {
        HasElement oldContent = oldChildren.get(targetRouterLayout);
        RouterLayout removeFrom = targetRouterLayout;
        // Recursively remove content of the all nested router
        // layouts of the given old content to be detached. This
        // is needed to let Dependency Injection frameworks to
        // re-create managed components with no
        // duplicates/leftovers.
        while (oldContent != null) {
            removeFrom.removeRouterLayoutContent(oldContent);
            if (oldContent instanceof RouterLayout) {
                removeFrom = (RouterLayout) oldContent;
            }
            oldContent = oldChildren.get(oldContent);
        }
    }
}
