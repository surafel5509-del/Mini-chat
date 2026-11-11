package com.minichat.app.core.di

import android.content.Context
import com.minichat.app.BuildConfig
import com.minichat.app.core.crypto.EncryptedSessionManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.serializer.KotlinXSerializer
import io.github.jan.supabase.storage.Storage
import kotlinx.serialization.json.Json
import javax.inject.Singleton

/**
 * Single Supabase client for the whole app.
 *
 * Security notes:
 *  * only the publishable (anon) key is compiled into the APK — never a service role key;
 *  * the session is persisted through [EncryptedSessionManager] (keystore backed);
 *  * all authorization decisions are enforced by Postgres row level security, the client is
 *    never trusted.
 */
@Module
@InstallIn(SingletonComponent::class)
object SupabaseModule {

    @Provides
    @Singleton
    fun provideSupabaseClient(
        @ApplicationContext context: Context,
        json: Json,
        sessionManager: EncryptedSessionManager,
    ): SupabaseClient = createSupabaseClient(
        supabaseUrl = BuildConfig.SUPABASE_URL,
        supabaseKey = BuildConfig.SUPABASE_PUBLISHABLE_KEY,
    ) {
        defaultSerializer = KotlinXSerializer(json)

        install(Auth) {
            // The confirmation and recovery emails come back as
            // minichat://auth-callback. supabase-kt builds that redirect from
            // scheme+host, and every auth call inherits it — signUp,
            // resendEmail and resetPasswordForEmail all point here without each
            // call site repeating the string.
            scheme = "minichat"
            host = "auth-callback"

            alwaysAutoRefresh = true
            autoLoadFromStorage = true
            autoSaveToStorage = true
            enableLifecycleCallbacks = true
            this.sessionManager = sessionManager
        }
        install(Postgrest)
        install(Realtime)
        install(Storage)
    }
}
