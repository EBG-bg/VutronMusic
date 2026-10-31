export interface VutronCommentTarget { targetType: 'track' | 'mv' | 'playlist' | 'album'; targetId: string }
export interface VutronCommentQuery extends VutronCommentTarget { sort: 99 | 2 | 3; page: number; cursor: string }
export interface VutronComment {
  id: string; userId: string; nickname: string; avatarUrl?: string; content: string; deleted: boolean
  time: number; liked: boolean; likedCount: number; replyCount: number
  replies: { id: string; nickname: string; content: string }[]
}
export interface VutronCommentPage { comments: VutronComment[]; total: number; hasMore: boolean; cursor: string; nextPage: number; sort: 99 | 2 | 3 }
