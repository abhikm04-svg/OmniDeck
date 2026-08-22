package com.omnideck.shell.di

import com.omnideck.generated.GeneratedModuleRegistry
import com.omnideck.kernel.loader.BundledModuleFactories
import com.omnideck.kernel.router.NavigationCommandSink
import com.omnideck.kernel.services.PermissionRequester
import com.omnideck.shell.ActivityPermissionRequester
import com.omnideck.shell.navigation.ShellNavigationSink
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * The three places the kernel deliberately cannot reach on its own: the Compose
 * navigation host, the Activity that owns ActivityResult contracts, and the
 * compile-time module registry. All are bound here, in the Shell, so the kernel stays
 * free of UI, Activity and build-generated references and remains unit-testable.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class ShellModule {

    @Binds
    @Singleton
    abstract fun bindNavigationSink(impl: ShellNavigationSink): NavigationCommandSink

    @Binds
    @Singleton
    abstract fun bindPermissionRequester(impl: ActivityPermissionRequester): PermissionRequester

    companion object {

        /**
         * OD-202. `GeneratedModuleRegistry` is written by `:tools:module-processor`
         * from whatever modules were compiled in, so this line does not change when a
         * module is added — which is the only reason a Shell file may mention modules
         * at all (goal G1).
         */
        @Provides
        @Singleton
        fun bundledModuleFactories(): BundledModuleFactories =
            BundledModuleFactories { GeneratedModuleRegistry.factories }
    }
}
