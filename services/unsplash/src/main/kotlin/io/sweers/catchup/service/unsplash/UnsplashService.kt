/*
 * Copyright (c) 2018 Zac Sweers
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.sweers.catchup.service.unsplash

import com.squareup.moshi.Moshi
import dagger.Binds
import dagger.Lazy
import dagger.Module
import dagger.Provides
import dagger.Reusable
import dagger.multibindings.IntoMap
import io.reactivex.Maybe
import io.sweers.catchup.service.api.CatchUpItem
import io.sweers.catchup.service.api.DataRequest
import io.sweers.catchup.service.api.DataResult
import io.sweers.catchup.service.api.ImageInfo
import io.sweers.catchup.service.api.LinkHandler
import io.sweers.catchup.service.api.Service
import io.sweers.catchup.service.api.ServiceKey
import io.sweers.catchup.service.api.ServiceMeta
import io.sweers.catchup.service.api.ServiceMetaKey
import io.sweers.catchup.service.api.VisualService
import io.sweers.catchup.service.unsplash.UnsplashApi.OrderBy.POPULAR
import io.sweers.catchup.serviceregistry.annotations.Meta
import io.sweers.catchup.serviceregistry.annotations.ServiceModule
import io.sweers.catchup.util.data.adapters.ISO8601InstantAdapter
import io.sweers.catchup.util.network.AuthInterceptor
import okhttp3.OkHttpClient
import org.threeten.bp.Instant
import retrofit2.Retrofit
import retrofit2.adapter.rxjava2.RxJava2CallAdapterFactory
import retrofit2.converter.moshi.MoshiConverterFactory
import javax.inject.Inject
import javax.inject.Qualifier

@Qualifier
private annotation class InternalApi

private const val SERVICE_KEY = "unsplash"

internal class UnsplashService @Inject constructor(
    @InternalApi private val serviceMeta: ServiceMeta,
    private val api: UnsplashApi,
    private val linkHandler: LinkHandler)
  : VisualService {

  override fun meta() = serviceMeta

  override fun fetchPage(request: DataRequest): Maybe<DataResult> {
    val page = request.pageId.toInt()
    return api.getPhotos(page, 50, POPULAR)
        .flattenAsObservable { it }
        .map {
          CatchUpItem(
              id = it.id.hashCode().toLong(),
              title = "",
              score =
              "\u2665\uFE0E" // Because lol: https://code.google.com/p/android/issues/detail?id=231068
                  to it.likes,
              timestamp = it.createdAt,
              author = it.user.name,
              source = null,
              tag = null,
              itemClickUrl = it.links.html,
              imageInfo = ImageInfo(
                  it.urls.small,
                  false,
                  null
              )
          )
        }
        .toList()
        .map { DataResult(it, (page + 1).toString()) }
        .toMaybe()
  }

  override fun linkHandler() = linkHandler
}

@Meta
@ServiceModule
@Module
abstract class UnsplashMetaModule {

  @IntoMap
  @ServiceMetaKey(SERVICE_KEY)
  @Binds
  internal abstract fun unsplashServiceMeta(@InternalApi meta: ServiceMeta): ServiceMeta

  @Module
  companion object {

    @InternalApi
    @Provides
    @Reusable
    @JvmStatic
    internal fun provideUnsplashServiceMeta() = ServiceMeta(
        SERVICE_KEY,
        R.string.unsplash,
        R.color.unsplashAccent,
        R.drawable.logo_unsplash,
        isVisual = true,
        pagesAreNumeric = true,
        firstPageKey = "1",
        enabled = BuildConfig.UNSPLASH_API_KEY.run { !isNullOrEmpty() && !equals("null") }
    )
  }
}

@ServiceModule
@Module(includes = [UnsplashMetaModule::class])
abstract class UnsplashModule {

  @IntoMap
  @ServiceKey(SERVICE_KEY)
  @Binds
  internal abstract fun unsplashService(unsplashService: UnsplashService): Service

  @Module
  companion object {

    @Provides
    @JvmStatic
    @InternalApi
    internal fun provideUnsplashMoshi(moshi: Moshi): Moshi {
      return moshi.newBuilder()
          .add(Instant::class.java, ISO8601InstantAdapter())
          .build()
    }

    @Provides
    @JvmStatic
    @InternalApi
    internal fun provideUnsplashOkHttpClient(client: OkHttpClient): OkHttpClient {
      return client.newBuilder()
          .addInterceptor {
            it.proceed(it.request().newBuilder()
                .addHeader("Accept-Version", "v1")
                .build())
          }
          .addInterceptor(AuthInterceptor("Client-ID", BuildConfig.UNSPLASH_API_KEY))
          .build()
    }

    @Provides
    @JvmStatic
    internal fun provideUnsplashService(@InternalApi client: Lazy<OkHttpClient>,
        @InternalApi moshi: Moshi,
        rxJavaCallAdapterFactory: RxJava2CallAdapterFactory): UnsplashApi {
      return Retrofit.Builder().baseUrl(UnsplashApi.ENDPOINT)
          .callFactory { client.get().newCall(it) }
          .addCallAdapterFactory(rxJavaCallAdapterFactory)
          .addConverterFactory(MoshiConverterFactory.create(moshi))
          .validateEagerly(BuildConfig.DEBUG)
          .build()
          .create(UnsplashApi::class.java)
    }
  }
}
