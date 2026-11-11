package com.minichat.app.core.di

import com.minichat.app.data.realtime.RealtimeService
import com.minichat.app.data.repository.AuthRepositoryImpl
import com.minichat.app.data.repository.ChatRepositoryImpl
import com.minichat.app.data.repository.GroupRepositoryImpl
import com.minichat.app.data.repository.NoteRepositoryImpl
import com.minichat.app.data.repository.ProfileRepositoryImpl
import com.minichat.app.domain.repository.AuthRepository
import com.minichat.app.domain.repository.ChatRepository
import com.minichat.app.domain.repository.GroupRepository
import com.minichat.app.domain.repository.NoteRepository
import com.minichat.app.domain.repository.ProfileRepository
import com.minichat.app.domain.repository.RealtimeRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindAuthRepository(impl: AuthRepositoryImpl): AuthRepository

    @Binds
    @Singleton
    abstract fun bindChatRepository(impl: ChatRepositoryImpl): ChatRepository

    @Binds
    @Singleton
    abstract fun bindProfileRepository(impl: ProfileRepositoryImpl): ProfileRepository

    @Binds
    @Singleton
    abstract fun bindRealtimeRepository(impl: RealtimeService): RealtimeRepository

    @Binds
    @Singleton
    abstract fun bindNoteRepository(impl: NoteRepositoryImpl): NoteRepository

    @Binds
    @Singleton
    abstract fun bindGroupRepository(impl: GroupRepositoryImpl): GroupRepository
}
