package com.github.nordinh.metrics.resteasy;

import static com.codahale.metrics.MetricRegistry.name;
import static com.google.common.collect.Maps.newHashMap;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.Map;

import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.container.ContainerRequestFilter;
import javax.ws.rs.container.ContainerResponseContext;
import javax.ws.rs.container.ContainerResponseFilter;
import javax.ws.rs.container.DynamicFeature;
import javax.ws.rs.container.ResourceInfo;
import javax.ws.rs.core.FeatureContext;

import org.jboss.resteasy.core.interception.PostMatchContainerRequestContext;

import com.codahale.metrics.Meter;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.Timer;
import com.codahale.metrics.annotation.Metered;
import com.codahale.metrics.annotation.Timed;

public class MetricsFeature implements DynamicFeature {

	private final MetricRegistry registry;
	private Map<Method, Timer> timers = newHashMap();
	private Map<Method, Meter> meters = newHashMap();
	private TimerRequestEventListener timerRequestEventListener;
	private MeterRequestEventListener meterRequestEventListener;

	public MetricsFeature(MetricRegistry registry) {
		this.registry = registry;
		timerRequestEventListener = new TimerRequestEventListener(timers);
		meterRequestEventListener = new MeterRequestEventListener(meters);
	}

	private static class TimerRequestEventListener implements ContainerRequestFilter, ContainerResponseFilter {
		private final Map<Method, Timer> timers;
		private Timer.Context context = null;

		public TimerRequestEventListener(final Map<Method, Timer> timers) {
			this.timers = timers;
		}

		public void filter(ContainerRequestContext requestContext) throws IOException {
			PostMatchContainerRequestContext postMatchContainerRequestContext = (PostMatchContainerRequestContext) requestContext;
			final Timer timer = this.timers.get(postMatchContainerRequestContext.getResourceMethod().getMethod());
			if (timer != null) {
				this.context = timer.time();
			}
		}

		public void filter(ContainerRequestContext requestContext, ContainerResponseContext responseContext)
				throws IOException {
			if (this.context != null) {
				this.context.close();
			}
		}
	}

	private static class MeterRequestEventListener implements ContainerRequestFilter {
		private final Map<Method, Meter> meters;

		public MeterRequestEventListener(final Map<Method, Meter> meters) {
			this.meters = meters;
		}

		public void filter(ContainerRequestContext requestContext) throws IOException {
			PostMatchContainerRequestContext postMatchContainerRequestContext = (PostMatchContainerRequestContext) requestContext;
			final Meter meter = this.meters.get(postMatchContainerRequestContext.getResourceMethod().getMethod());
			if (meter != null) {
				meter.mark();
			}
		}
	}

	public void configure(ResourceInfo resourceInfo, FeatureContext context) {
		registerTimedAnnotations(getResourceMethod(resourceInfo));
		registerMeteredAnnotations(getResourceMethod(resourceInfo));

		context.register(timerRequestEventListener);
		context.register(meterRequestEventListener);
	}

	private void registerTimedAnnotations(final Method method) {
		final Timed annotation = method.getAnnotation(Timed.class);

		if (annotation != null) {
			timers.put(method, timerMetric(registry, method, annotation));
		}
	}

	private void registerMeteredAnnotations(final Method method) {
		final Metered annotation = method.getAnnotation(Metered.class);

		if (annotation != null) {
			meters.put(method, meterMetric(registry, method, annotation));
		}
	}

	private static Timer timerMetric(final MetricRegistry registry,
			final Method method,
			final Timed timed) {
		final String name = chooseName(timed.name(), timed.absolute(), method);
		return registry.timer(name);
	}

	private static Meter meterMetric(final MetricRegistry registry,
			final Method method,
			final Metered metered) {
		final String name = chooseName(metered.name(), metered.absolute(), method);
		return registry.meter(name);
	}

	protected static String chooseName(final String explicitName, final boolean absolute, final Method method,
			final String... suffixes) {
		if (explicitName != null && !explicitName.isEmpty()) {
			if (absolute) {
				return explicitName;
			}
			return name(method.getDeclaringClass(), explicitName);
		}

		return name(name(method.getDeclaringClass(),
				method.getName()),
				suffixes);
	}

	private Method getResourceMethod(ResourceInfo resourceInfo) {
		try {
			String methodName = resourceInfo.getResourceMethod().getName();
			Class<?>[] methodParameterTypes = resourceInfo.getResourceMethod().getParameterTypes();
			return resourceInfo
					.getResourceClass()
					.getMethod(methodName, methodParameterTypes);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

}
