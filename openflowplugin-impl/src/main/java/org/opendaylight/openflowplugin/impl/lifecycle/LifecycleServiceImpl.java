/**
 * Copyright (c) 2016 Pantheon Technologies s.r.o. and others. All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package org.opendaylight.openflowplugin.impl.lifecycle;

import com.google.common.base.MoreObjects;
import com.google.common.base.Verify;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.opendaylight.mdsal.singleton.common.api.ClusterSingletonServiceProvider;
import org.opendaylight.mdsal.singleton.common.api.ClusterSingletonServiceRegistration;
import org.opendaylight.mdsal.singleton.common.api.ServiceGroupIdentifier;
import org.opendaylight.openflowplugin.api.openflow.OFPContext.ContextState;
import org.opendaylight.openflowplugin.api.openflow.device.DeviceContext;
import org.opendaylight.openflowplugin.api.openflow.device.DeviceInfo;
import org.opendaylight.openflowplugin.api.openflow.device.handlers.ClusterInitializationPhaseHandler;
import org.opendaylight.openflowplugin.api.openflow.device.handlers.DeviceRemovedHandler;
import org.opendaylight.openflowplugin.api.openflow.lifecycle.LifecycleService;
import org.opendaylight.openflowplugin.api.openflow.lifecycle.MastershipChangeListener;
import org.opendaylight.yang.gen.v1.urn.opendaylight.role.service.rev150727.SetRoleOutput;
import org.opendaylight.yangtools.yang.common.RpcResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LifecycleServiceImpl implements LifecycleService {

    private static final Logger LOG = LoggerFactory.getLogger(LifecycleServiceImpl.class);

    private final List<DeviceRemovedHandler> deviceRemovedHandlers = new ArrayList<>();
    private final MastershipChangeListener mastershipChangeListener;
    private final ExecutorService executorService;
    private ClusterSingletonServiceRegistration registration;
    private ClusterInitializationPhaseHandler clusterInitializationPhaseHandler;
    private ServiceGroupIdentifier serviceGroupIdentifier;
    private DeviceInfo deviceInfo;
    private volatile ContextState state = ContextState.INITIALIZATION;

    public LifecycleServiceImpl(@Nonnull final MastershipChangeListener mastershipChangeListener,
                                @Nonnull final ExecutorService executorService) {
        this.mastershipChangeListener = mastershipChangeListener;
        this.executorService = executorService;
    }

    @Override
    public void makeDeviceSlave(final DeviceContext deviceContext) {
        deviceInfo = MoreObjects.firstNonNull(deviceInfo, deviceContext.getDeviceInfo());

        Futures.addCallback(deviceContext.makeDeviceSlave(), new FutureCallback<RpcResult<SetRoleOutput>>() {
            @Override
            public void onSuccess(@Nullable RpcResult<SetRoleOutput> setRoleOutputRpcResult) {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Role SLAVE was successfully propagated on device, node {}",
                            deviceContext.getDeviceInfo().getLOGValue());
                }
                mastershipChangeListener.onSlaveRoleAcquired(deviceInfo);
            }

            @Override
            public void onFailure(@Nonnull Throwable throwable) {
                LOG.warn("Was not able to set role SLAVE to device on node {} ",
                        deviceContext.getDeviceInfo().getLOGValue());
                mastershipChangeListener.onSlaveRoleNotAcquired(deviceInfo);
            }
        });
    }

    @Override
    public void instantiateServiceInstance() {
        executorService.submit(() -> {
            LOG.info("Starting clustering services for node {}", deviceInfo.getLOGValue());

            if (!clusterInitializationPhaseHandler.onContextInstantiateService(mastershipChangeListener)) {
                mastershipChangeListener.onNotAbleToStartMastershipMandatory(deviceInfo, "Cannot initialize device.");
            }
        });
    }

    @Override
    public ListenableFuture<Void> closeServiceInstance() {
        LOG.info("Closing clustering services for node {}", deviceInfo.getLOGValue());
        return Futures.immediateFuture(null);
    }

    @Override
    @Nonnull
    public ServiceGroupIdentifier getIdentifier() {
        return this.serviceGroupIdentifier;
    }

    @Override
    public void close() {
        if (ContextState.TERMINATION.equals(state)) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("LifecycleService for node {} is already in TERMINATION state.", deviceInfo.getLOGValue());
            }
        } else {
            state = ContextState.TERMINATION;

            // We are closing, so cleanup all managers now
            deviceRemovedHandlers.forEach(h -> h.onDeviceRemoved(deviceInfo));

            // If we are still registered and we are not already closing, then close the registration
            if (Objects.nonNull(registration)) {
                try {
                    LOG.info("Closing clustering services registration for node {}", deviceInfo.getLOGValue());
                    registration.close();
                    registration = null;
                } catch (final Exception e) {
                    LOG.warn("Failed to close clustering services registration for node {} with exception: ",
                            deviceInfo.getLOGValue(), e);
                }
            }
        }
    }

    @Override
    public void registerService(@Nonnull final ClusterSingletonServiceProvider singletonServiceProvider,
                                @Nonnull final DeviceContext deviceContext) {
        Verify.verify(Objects.isNull(registration));
        this.clusterInitializationPhaseHandler = deviceContext;
        this.serviceGroupIdentifier = deviceContext.getServiceIdentifier();
        this.deviceInfo = deviceContext.getDeviceInfo();
        this.registration = Verify.verifyNotNull(
                singletonServiceProvider.registerClusterSingletonService(this));

        LOG.info("Registered clustering services for node {}", deviceInfo.getLOGValue());
    }

    @Override
    public void registerDeviceRemovedHandler(@Nonnull final DeviceRemovedHandler deviceRemovedHandler) {
        if (!deviceRemovedHandlers.contains(deviceRemovedHandler)) {
            deviceRemovedHandlers.add(deviceRemovedHandler);
        }
    }

}
