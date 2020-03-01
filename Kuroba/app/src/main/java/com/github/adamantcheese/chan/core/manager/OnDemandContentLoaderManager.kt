package com.github.adamantcheese.chan.core.manager

import android.annotation.SuppressLint
import androidx.annotation.GuardedBy
import com.github.adamantcheese.chan.core.loader.LoaderBatchResult
import com.github.adamantcheese.chan.core.loader.LoaderResult
import com.github.adamantcheese.chan.core.loader.OnDemandContentLoader
import com.github.adamantcheese.chan.core.loader.PostLoaderData
import com.github.adamantcheese.chan.core.model.Post
import com.github.adamantcheese.chan.core.model.orm.Loadable
import com.github.adamantcheese.chan.utils.BackgroundUtils
import com.github.adamantcheese.chan.utils.Logger
import com.github.adamantcheese.chan.utils.PostUtils.getPostUniqueId
import io.reactivex.Flowable
import io.reactivex.Scheduler
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.functions.BiFunction
import io.reactivex.processors.PublishProcessor
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

class OnDemandContentLoaderManager(
        private val workerScheduler: Scheduler,
        private val loaders: Set<OnDemandContentLoader>
) {
    private val rwLock = ReentrantReadWriteLock()

    // HashMap<LoadableUid, HashMap<PostUid, PostLoaderData>>()
    @GuardedBy("rwLock")
    private val activeLoaders = HashMap<String, HashMap<String, PostLoaderData>>()

    private val postLoaderRxQueue = PublishProcessor.create<PostLoaderData>()
    private val postUpdateRxQueue = PublishProcessor.create<LoaderBatchResult>()

    init {
        Logger.d(TAG, "Loaders count = ${loaders.size}")
        initPostLoaderRxQueue()
    }

    @SuppressLint("CheckResult")
    private fun initPostLoaderRxQueue() {
        postLoaderRxQueue
                .onBackpressureBuffer(MIN_QUEUE_CAPACITY, false, true)
                .flatMap { value ->
                    return@flatMap Flowable.just(value)
                            // Add LOADING_DELAY_TIME_SECONDS seconds delay to every emitted event.
                            // We do that so that we don't download everything when user quickly
                            // scrolls through posts. In other words, we only start running the
                            // loader after LOADING_DELAY_TIME_SECONDS seconds has passed since
                            // onPostBind() was called. If onPostUnbind() was called during that
                            // time frame we cancel the loader if it has already started loading or
                            // just do nothing if it has yet started loading.
                            .zipWith(
                                    Flowable.timer(
                                            LOADING_DELAY_TIME_SECONDS,
                                            TimeUnit.SECONDS,
                                            workerScheduler
                                    ),
                                    ZIP_FUNC
                            )
                }
                .filter { (postLoaderData, _) -> isStillActive(postLoaderData) }
                .map { (postLoaderData, _) -> postLoaderData }
                .flatMap { postLoaderData -> processLoaders(postLoaderData) }
                .subscribe({
                    // Do nothing
                }, { error ->
                    throw RuntimeException("$TAG Uncaught exception!!! " +
                            "workerQueue is in error state now!!! " +
                            "This should not happen!!!, original error = " + error.message)
                }, {
                    throw RuntimeException(
                            "$TAG workerQueue stream has completed!!! This should not happen!!!"
                    )
                })
    }

    private fun processLoaders(postLoaderData: PostLoaderData): Flowable<Unit>? {
        return Flowable.fromIterable(loaders)
                .flatMapSingle { loader ->
                    return@flatMapSingle loader.startLoading(postLoaderData)
                            .timeout(MAX_LOADER_LOADING_TIME_SECONDS, TimeUnit.SECONDS, workerScheduler)
                            .onErrorReturnItem(LoaderResult.Error(loader.loaderType))
                }
                .toList()
                .map { results -> LoaderBatchResult(postLoaderData.loadable, postLoaderData.post, results) }
                .doOnSuccess(postUpdateRxQueue::onNext)
                .map { Unit }
                .toFlowable()
    }

    fun listenPostContentUpdates(): Flowable<LoaderBatchResult> {
        BackgroundUtils.ensureMainThread()

        return postUpdateRxQueue
                .hide()
                .observeOn(AndroidSchedulers.mainThread())
    }

    fun onPostBind(loadable: Loadable, post: Post) {
        BackgroundUtils.ensureMainThread()
        check(loaders.isNotEmpty()) { "No loaders!" }

        val loadableUid = loadable.uniqueId
        val postUid = getPostUniqueId(loadable, post)

        Logger.d(TAG, "onPostBind called for $postUid")

        val postLoaderData = PostLoaderData(loadable, post)
        if (everythingIsAlreadyCached(postLoaderData)) {
            return
        }

        val alreadyAdded = rwLock.write {
            if (!activeLoaders.containsKey(loadableUid)) {
                activeLoaders[loadableUid] = hashMapOf()
            }

            if (activeLoaders[loadableUid]!!.containsKey(postUid)) {
                return@write true
            }

            activeLoaders[loadableUid]!![postUid] = postLoaderData
            return@write false
        }

        if (alreadyAdded) {
            return
        }

        postLoaderRxQueue.onNext(postLoaderData)
    }

    fun onPostUnbind(loadable: Loadable, post: Post) {
        BackgroundUtils.ensureMainThread()
        check(loaders.isNotEmpty()) { "No loaders!" }

        val loadableUid = loadable.uniqueId
        val postUid = getPostUniqueId(loadable, post)

        Logger.d(TAG, "onPostUnbind called for $postUid")

        val postLoaderData = rwLock.write {
            val postLoaderData = activeLoaders[loadableUid]?.remove(postUid)
                    ?: return@write null

            loaders.forEach { loader -> loader.cancelLoading(postLoaderData) }
            postLoaderData.disposeAll()

            return@write postLoaderData
        } ?: return

        postLoaderData.disposeAll()
    }

    fun cancelAllForLoadable(loadable: Loadable) {
        BackgroundUtils.ensureMainThread()
        val loadableUid = loadable.uniqueId

        Logger.d(TAG, "cancelAllForLoadable called for $loadableUid")

        rwLock.write {
            val postLoaderDataList = activeLoaders[loadableUid]
                    ?: return@write

            postLoaderDataList.values.forEach { postLoaderData ->
                loaders.forEach { loader -> loader.cancelLoading(postLoaderData) }
                postLoaderData.disposeAll()
            }

            postLoaderDataList.clear()

            activeLoaders.remove(loadableUid)
        }
    }

    private fun everythingIsAlreadyCached(postLoaderData: PostLoaderData): Boolean {
        val allLoadersAlreadyCached = loaders.all { loader -> loader.isAlreadyCached(postLoaderData) }
        if (allLoadersAlreadyCached) {
            val results = loaders.map { loader -> LoaderResult.Success(loader.loaderType) }

            postUpdateRxQueue.onNext(
                    LoaderBatchResult(postLoaderData.loadable, postLoaderData.post, results)
            )
            return true
        }

        return false
    }

    private fun isStillActive(postLoaderData: PostLoaderData): Boolean {
        return rwLock.read {
            val loadableUid = postLoaderData.getLoadableUniqueId()
            val postUid = postLoaderData.getPostUniqueId()

            return@read activeLoaders[loadableUid]?.containsKey(postUid)
                    ?: false
        }
    }

    companion object {
        private const val TAG = "OnDemandContentLoaderManager"
        private const val MIN_QUEUE_CAPACITY = 32
        private const val LOADING_DELAY_TIME_SECONDS = 1L
        private const val MAX_LOADER_LOADING_TIME_SECONDS = 10L

        private val ZIP_FUNC = BiFunction<PostLoaderData, Long, Pair<PostLoaderData, Long>> { postLoaderData, timer ->
            Pair(postLoaderData, timer)
        }
    }
}