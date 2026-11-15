package org.evsyukov.post.data.db

/**
 * The state of a post after its deliveries changed (R28, Q1):
 * - a draft, a sent post and a post without deliveries keep their state;
 * - a delivery in progress makes the post SENDING;
 * - waiting deliveries keep a held post HELD; otherwise the post is SENDING once any delivery has made
 *   an attempt, and QUEUED before that;
 * - with nothing pending, a failed delivery makes the post FAILED; when all succeeded the post stays
 *   as it is until [org.evsyukov.post.data.posts.PostsRepository.markSent] records it as sent.
 */
internal fun derivedPostState(current: PostState, deliveries: List<DeliveryEntity>): PostState {
    if (current == PostState.DRAFT || current == PostState.SENT || deliveries.isEmpty()) return current
    val states = deliveries.map { it.state }
    return when {
        DeliveryState.SENDING in states -> PostState.SENDING
        DeliveryState.WAITING in states -> when {
            current == PostState.HELD -> PostState.HELD
            deliveries.any { it.attempts > 0 } -> PostState.SENDING
            else -> PostState.QUEUED
        }
        DeliveryState.FAILED in states -> PostState.FAILED
        else -> current
    }
}

/** Sets the state of post [postId] from its deliveries; call it inside the transaction that changed them. */
internal suspend fun PostDatabase.refreshPostState(postId: Long, now: Long) {
    val post = postDao().get(postId) ?: return
    val state = derivedPostState(post.state, deliveryDao().forPost(postId))
    if (state != post.state) postDao().updateState(postId, state, now)
}
