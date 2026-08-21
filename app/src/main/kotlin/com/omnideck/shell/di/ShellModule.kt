package com.omnideck.shell.di

import com.omnideck.kernel.router.NavigationCommandSink
import com.omnideck.kernel.services.PermissionRequester
import com.omnideck.shell.ActivityPermissionRequester
import com.omnideck.shell.navigation.ShellNavigationSink
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * The two places the kernel deliberately cannot reach on its own: the Compose
 * navigation host, and the Activity that owns ActivityResult contracts. Both are
 * bound here, in the Shell, so the kernel stays free of UI and Activity references
 * and remains unit-testable.
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
}
