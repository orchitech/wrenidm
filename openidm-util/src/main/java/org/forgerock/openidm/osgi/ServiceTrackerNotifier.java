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
 * information: "Portions Copyrighted [year] [name of copyright owner]".
 *
 * Copyright © 2011-2016 ForgeRock AS. All rights reserved.
 */
package org.forgerock.openidm.osgi;

import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.Filter;
import org.osgi.util.tracker.ServiceTracker;
import org.osgi.util.tracker.ServiceTrackerCustomizer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An enhancement to ServiceTracker to allow listeners to be notified on changes
 * Avoids having to extend service tracker each time this is desired,
 * or abusing ServiceTrackerCustomizer for this purpose.
 *
 * @param <S> The type of the service being tracked.
 * @param <T> The type of the tracked object.
 */
public class ServiceTrackerNotifier<S, T> extends ServiceTracker<S, T> {

    private final static Logger logger = LoggerFactory.getLogger(ServiceTrackerNotifier.class);

    ServiceTrackerListener<S,T> listener;
    BundleContext context;

    public ServiceTrackerNotifier(BundleContext context, Filter filter,
            ServiceTrackerCustomizer<S,T> customizer, ServiceTrackerListener<S,T> listener) {
        super(context, filter, customizer);
        this.listener = listener;
        this.context = context;
    }

    public ServiceTrackerNotifier(BundleContext context, ServiceReference<S> reference,
            ServiceTrackerCustomizer<S,T> customizer, ServiceTrackerListener<S,T> listener) {
        super(context, reference, customizer);
        this.listener = listener;
        this.context = context;
    }

    public ServiceTrackerNotifier(BundleContext context, java.lang.String clazz,
            ServiceTrackerCustomizer<S,T> customizer, ServiceTrackerListener<S,T> listener) {
        super(context, clazz, customizer);
        this.listener = listener;
        this.context = context;
    }

    @Override
    public T addingService(ServiceReference<S> reference) {
        T service =  super.addingService(reference);
        if (service == null) {
            logger.warn("Framework issue, service in service tracker is null for {}",
                    ServiceUtil.getServicePid(reference.getProperties()));
        }
        if (listener != null) {
            listener.addedService(reference, service);
        }
        return service;
    }
    @Override
    public void removedService(ServiceReference<S> reference, T service) {
        if (listener != null) {
            listener.removedService(reference, service);
        }
        super.removedService(reference, service);
    }

    @Override
    public void modifiedService(ServiceReference<S> reference, T service) {
        if (service == null) {
            logger.warn("Framework issue, service in service tracker modified is null for {}",
                    ServiceUtil.getServicePid(reference.getProperties()));
        }
        if (listener != null) {
            listener.modifiedService(reference, service);
        }
        super.modifiedService(reference, service);
    }
}
