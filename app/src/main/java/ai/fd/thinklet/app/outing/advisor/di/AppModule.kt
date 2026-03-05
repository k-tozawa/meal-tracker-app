package ai.fd.thinklet.app.outing.advisor.di

import ai.fd.thinklet.app.outing.advisor.data.xfe.XFERepository
import ai.fd.thinklet.app.outing.advisor.data.xfe.XFERepositoryImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AppModule {

    @Binds
    @Singleton
    abstract fun bindXFERepository(
        xfeRepositoryImpl: XFERepositoryImpl
    ): XFERepository
}
