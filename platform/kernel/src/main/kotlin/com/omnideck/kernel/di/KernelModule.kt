package com.omnideck.kernel.di

import android.content.Context
import com.google.android.play.core.splitinstall.SplitInstallManagerFactory
import com.omnideck.core.DefaultDispatcherProvider
import com.omnideck.core.DispatcherProvider
import com.omnideck.kernel.events.EventBusImpl
import com.omnideck.kernel.lifecycle.HostInfo
import com.omnideck.kernel.loader.AssetModuleDescriptorSource
import com.omnideck.kernel.loader.BundledModuleProvider
import com.omnideck.kernel.loader.FeatureSplitProvider
import com.omnideck.kernel.loader.ModuleDescriptorSource
import com.omnideck.kernel.loader.ModuleProvider
import com.omnideck.kernel.router.RouterImpl
import com.omnideck.kernel.services.AnonymousAuthService
import com.omnideck.kernel.services.ConsentServiceImpl
import com.omnideck.kernel.services.InMemoryFeatureFlagService
import com.omnideck.kernel.services.NoEntitlementsBillingService
import com.omnideck.kernel.services.TelemetryHub
import com.omnideck.sdk.SemVer
import com.omnideck.sdk.capability.AuthService
import com.omnideck.sdk.capability.BillingService
import com.omnideck.sdk.capability.ConsentService
import com.omnideck.sdk.capability.EventBus
import com.omnideck.sdk.capability.FeatureFlagService
import com.omnideck.sdk.capability.Router
import com.omnideck.sdk.capability.TelemetryService
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.ElementsIntoSet
import javax.inject.Singleton

/**
 * ADR-002 — Hilt lives here and only here.
 *
 * Feature modules never see Dagger: they receive `PlatformServices`, which the Shell
 * obtains from this graph and hands over. That containment is what would let the
 * platform switch DI frameworks without touching a single module.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class KernelBindings {

    @Binds
    @Singleton
    abstract fun bindFlags(impl: InMemoryFeatureFlagService): FeatureFlagService

    @Binds
    @Singleton
    abstract fun bindEvents(impl: EventBusImpl): EventBus

    @Binds
    @Singleton
    abstract fun bindConsent(impl: ConsentServiceImpl): ConsentService

    @Binds
    @Singleton
    abstract fun bindAuth(impl: AnonymousAuthService): AuthService

    @Binds
    @Singleton
    abstract fun bindBilling(impl: NoEntitlementsBillingService): BillingService

    @Binds
    @Singleton
    abstract fun bindRouter(impl: RouterImpl): Router
}

@Module
@InstallIn(SingletonComponent::class)
object KernelModule {

    @Provides
    @Singleton
    fun dispatchers(): DispatcherProvider = DefaultDispatcherProvider

    /**
     * Platform-scoped telemetry (moduleId = null). Module-scoped views come from
     * `ModuleScopedServicesFactory`, never from here.
     */
    @Provides
    @Singleton
    fun telemetry(hub: TelemetryHub, consent: ConsentService): TelemetryService {
        hub.consent = consent
        return hub.scopedTo(moduleId = null)
    }

    @Provides
    @Singleton
    fun descriptorSource(
        @ApplicationContext context: Context,
        dispatchers: DispatcherProvider,
    ): ModuleDescriptorSource = AssetModuleDescriptorSource(context, dispatchers.io)

    /**
     * ADR-001: all three delivery mechanisms are registered as a set, and the
     * lifecycle manager picks by `DeliveryKind`. Adding the satellite provider in
     * Phase 5 is one more entry here and nothing else.
     */
    @Provides
    @ElementsIntoSet
    @Singleton
    fun moduleProviders(@ApplicationContext context: Context, dispatchers: DispatcherProvider): Set<ModuleProvider> =
        setOf(
            BundledModuleProvider(dispatchers.io),
            FeatureSplitProvider(
                context = context,
                splitInstallManager = SplitInstallManagerFactory.create(context),
                io = dispatchers.io,
            ),
        )

    /**
     * The host SDK version. Bumped deliberately, reviewed under ADR-004, and compared
     * against every module's declared `sdkRange` before it is allowed to initialise.
     */
    @Provides
    @Singleton
    fun hostSdkVersion(): SemVer = SemVer(1, 0, 0)

    @Provides
    @Singleton
    fun hostInfo(sdkVersion: SemVer): HostInfo = HostInfo(sdkVersion = sdkVersion, versionCode = 1)
}
