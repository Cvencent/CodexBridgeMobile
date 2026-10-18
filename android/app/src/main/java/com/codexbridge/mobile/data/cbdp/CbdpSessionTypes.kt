package com.codexbridge.mobile.data.cbdp

enum class CbdpSessionState {
    DISCONNECTED,
    CONNECTED,
    HELLO_SENT,
    AUTH_SENT,
    READY,
    STALE,
    CLOSING,
}

object CbdpAuthMode {
    const val PAIRING_TOKEN = 0
    const val HMAC_SHA256 = 1
    const val MTLS = 2
}

object CbdpContentKind {
    const val MESSAGE_BODY = 0
    const val TOOL_OUTPUT = 1
    const val ATTACHMENT = 2
    const val OTHER = 3
}

object CbdpScope {
    const val SUMMARY = 0
    const val THREAD = 1
    const val LIVE = 2
}

object CbdpTopic {
    const val SYSTEM_BRIDGE = 0x50000000L
    const val SYSTEM_DEVICES = 0x50000001L
    const val SYSTEM_RESYNC = 0x50000002L
    const val SYSTEM_THREAD_SUMMARIES = 0x50000003L
}

object CbdpMethod {
    const val STATUS_READ = 0x0001
    const val THREADS_LIST = 0x0002
    const val THREAD_READ = 0x0003
    const val THREAD_START = 0x0004
    const val TURN_START = 0x0005
    const val TURN_STEER = 0x0006
    const val TURN_INTERRUPT = 0x0007
    const val APPROVALS_LIST = 0x0008
    const val APPROVAL_RESOLVE = 0x0009
    const val THREAD_CLAIM = 0x000A
    const val THREAD_RELEASE = 0x000B
    const val MODELS_LIST = 0x000C
    const val PROJECTS_LIST = 0x000D
    const val DEVICE_POLICY_READ = 0x000E
    const val COMMAND_STATUS = 0x000F
    const val DEVICE_CONFIG_UPDATE = 0x0010
    const val THREAD_SEARCH = 0x0011
    const val THREAD_RENAME = 0x0012
    const val THREAD_ARCHIVE = 0x0013
    const val THREAD_UNARCHIVE = 0x0014
    const val THREAD_PIN = 0x0015
    const val THREAD_UNPIN = 0x0016
    const val THREAD_FORK = 0x0017
    const val THREAD_DELETE = 0x0018
    const val THREAD_RESTORE = 0x0019
    const val THREAD_SETTINGS_READ = 0x001A
    const val THREAD_SETTINGS_UPDATE = 0x001B
    const val THREAD_METADATA_UPDATE = 0x001C
    const val THREAD_REVERT = 0x001D
    const val THREAD_READ_STATE_UPDATE = 0x001E
    const val THREAD_GOAL_GET = 0x001F
    const val THREAD_GOAL_SET = 0x0020
    const val THREAD_GOAL_CLEAR = 0x0021
    const val QUEUE_ADD = 0x0030
    const val QUEUE_LIST = 0x0031
    const val QUEUE_UPDATE = 0x0032
    const val QUEUE_DELETE = 0x0033
    const val QUEUE_REORDER = 0x0034
    const val QUEUE_PROMOTE = 0x0035
    const val QUEUE_START = 0x0036
    const val GUIDANCE_SEND = 0x0040
    const val GUIDANCE_UPDATE = 0x0041
    const val GUIDANCE_CANCEL = 0x0042
    const val MESSAGE_EDIT = 0x0050
    const val MESSAGE_DELETE = 0x0051
    const val MESSAGE_RANGE_DELETE = 0x0052
    const val ATTACHMENT_ADD = 0x0060
    const val ATTACHMENT_LIST = 0x0061
    const val ATTACHMENT_REMOVE = 0x0062
    const val ATTACHMENT_UPLOAD_BEGIN = 0x0063
    const val ATTACHMENT_UPLOAD_CHUNK = 0x0064
    const val ATTACHMENT_UPLOAD_END = 0x0065
    const val ATTACHMENT_UPLOAD_CANCEL = 0x0066
    const val DRAFT_UPDATE = 0x0070
    const val DRAFT_DELETE = 0x0071
    const val INPUT_RESPOND = 0x0080
    const val TOOL_RESPOND = 0x0081
    const val MCP_RESPOND = 0x0082
    const val ACCOUNT_READ = 0x0090
    const val ACCOUNT_RATE_LIMITS_READ = 0x0091
    const val ACCOUNT_TOKEN_USAGE_READ = 0x0092
    const val CONTEXT_READ = 0x0093
    const val CONTEXT_COMPACT = 0x0094
    const val BATCH_EXECUTE = 0x009F
    const val MESSAGE_READ_UPDATE = 0x00A0
    const val ACTIVE_STATE_READ = 0x00B1
    const val ACTIVE_STATE_SET = 0x00B2
    const val EVENTS_SINCE = 0x00B3
    const val INPUT_CAPABILITIES_READ = 0x00C0
    const val CONTENT_READ = 0x00C1
}

object CbdpCommandState {
    const val ACCEPTED = 0
    const val COMPLETED = 1
    const val FAILED = 2
    const val REJECTED = 3
    const val UNKNOWN = 4
}

object CbdpErrorCode {
    const val OK = 0x0000
    const val NOT_FOUND = 0x0001
    const val BUSY = 0x0002
    const val UNAUTHORIZED = 0x0003
    const val CAPABILITY_NOT_ENABLED = 0x0004
    const val INVALID_FRAME = 0x0005
    const val INVALID_PARAM = 0x0006
    const val INVALID_STATE = 0x0007
    const val IDEMPOTENCY_CONFLICT = 0x0008
    const val STALE_CURSOR = 0x0009
    const val RESYNC_REQUIRED = 0x000A
    const val UPSTREAM_UNAVAILABLE = 0x000B
    const val WRITER_CONFLICT = 0x000C
    const val PAYLOAD_TOO_LARGE = 0x000D
    const val UNSUPPORTED_FEATURE = 0x000E
    const val TIMEOUT = 0x000F
    const val POLICY_DENIED = 0x0010
    const val REVISION_CONFLICT = 0x0011
    const val DUPLICATE = 0x0012
    const val RATE_LIMITED = 0x0013
    const val CANCELLED = 0x0014
    const val INTERNAL = 0x0015
    const val PROTOCOL_VERSION = 0x0016
    const val AUTH_EXPIRED = 0x0017
    const val TOKEN_ROTATED = 0x0018
    const val QUEUE_FULL = 0x0019
    const val FRAGMENT_ERROR = 0x001A
    const val CONTENT_UNAVAILABLE = 0x001B
}

object CbdpEvent {
    const val THREAD_CREATED = 0x0101
    const val THREAD_UPDATED = 0x0102
    const val THREAD_TITLE_CHANGED = 0x0103
    const val THREAD_STATUS_CHANGED = 0x0104
    const val THREAD_OWNER_CHANGED = 0x0105
    const val THREAD_DELETED = 0x0106
    const val TURN_STARTED = 0x0201
    const val TURN_UPDATED = 0x0202
    const val TURN_COMPLETED = 0x0203
    const val TURN_FAILED = 0x0204
    const val TURN_INTERRUPTED = 0x0205
    const val TURN_PROGRESS = 0x0206
    const val MESSAGE_APPENDED = 0x0301
    const val MESSAGE_UPDATED = 0x0302
    const val MESSAGE_READ = 0x0303
    const val MESSAGE_DELETED = 0x0304
    const val APPROVAL_REQUESTED = 0x0401
    const val APPROVAL_RESOLVED = 0x0402
    const val APPROVAL_EXPIRED = 0x0403
    const val COMMAND_ACCEPTED = 0x0501
    const val COMMAND_PROGRESS = 0x0502
    const val COMMAND_COMPLETED = 0x0503
    const val COMMAND_FAILED = 0x0504
    const val COMMAND_UNKNOWN = 0x0505
    const val DEVICE_ONLINE = 0x0601
    const val DEVICE_OFFLINE = 0x0602
    const val DEVICE_POLICY_CHANGED = 0x0603
    const val DEVICE_WRITER_CHANGED = 0x0604
    const val DEVICE_LOAD = 0x0605
    const val SYSTEM_RESYNC_REQUIRED = 0x0701
    const val SYSTEM_SHUTDOWN = 0x0702
    const val SYSTEM_UPSTREAM_CHANGED = 0x0703
    const val QUEUE_CHANGED = 0x0801
    const val QUEUE_ITEM_ADDED = 0x0802
    const val QUEUE_ITEM_UPDATED = 0x0803
    const val QUEUE_ITEM_STARTED = 0x0804
    const val QUEUE_ITEM_COMPLETED = 0x0805
    const val QUEUE_ITEM_FAILED = 0x0806
    const val QUEUE_ITEM_CANCELLED = 0x0807
    const val QUEUE_REORDERED = 0x0808
    const val GUIDANCE_REQUESTED = 0x0901
    const val GUIDANCE_UPDATED = 0x0902
    const val GUIDANCE_APPLIED = 0x0903
    const val GUIDANCE_CANCELLED = 0x0904
    const val GUIDANCE_FAILED = 0x0905
    const val MESSAGE_REVISED = 0x0A01
    const val MESSAGE_RANGE_DELETED = 0x0A02
    const val THREAD_REVERTED = 0x0A03
    const val THREAD_BRANCHED = 0x0A04
    const val TOMBSTONE_CHANGED = 0x0A05
    const val REVISION_CONFLICT = 0x0A06
    const val THREAD_ARCHIVED = 0x0B01
    const val THREAD_UNARCHIVED = 0x0B02
    const val THREAD_PINNED = 0x0B03
    const val THREAD_UNPINNED = 0x0B04
    const val THREAD_FORKED = 0x0B05
    const val THREAD_RESTORED = 0x0B06
    const val THREAD_SETTINGS_CHANGED = 0x0B07
    const val THREAD_METADATA_CHANGED = 0x0B08
    const val THREAD_GOAL_CHANGED = 0x0B09
    const val THREAD_GOAL_CLEARED = 0x0B0A
    const val THREAD_READ_STATE_CHANGED = 0x0B0B
    const val THREAD_ACTIVITY_CHANGED = 0x0B0C
    const val INPUT_REQUESTED = 0x0C01
    const val INPUT_ANSWERED = 0x0C02
    const val INPUT_CANCELLED = 0x0C03
    const val ITEM_STARTED = 0x0C04
    const val ITEM_UPDATED = 0x0C05
    const val ITEM_COMPLETED = 0x0C06
    const val ITEM_FAILED = 0x0C07
    const val TOOL_PROGRESS = 0x0C08
    const val TOOL_COMPLETED = 0x0C09
    const val TOOL_FAILED = 0x0C0A
    const val MCP_STATUS_CHANGED = 0x0C0B
    const val MCP_REQUEST = 0x0C0C
    const val MCP_OAUTH_COMPLETED = 0x0C0D
    const val NOTICE_CREATED = 0x0C0E
    const val SERVER_REQUEST_CREATED = 0x0C0F
    const val SERVER_REQUEST_RESOLVED = 0x0C10
    const val PLAN_UPDATED = 0x0C11
    const val REASONING_SUMMARY = 0x0C12
    const val TOOL_REDACTED = 0x0C13
    const val ATTACHMENT_ADDED = 0x0D01
    const val ATTACHMENT_UPDATED = 0x0D02
    const val ATTACHMENT_REMOVED = 0x0D03
    const val ATTACHMENT_UPLOAD_STATE = 0x0D04
    const val DRAFT_UPDATED = 0x0D05
    const val DRAFT_DELETED = 0x0D06
    const val DRAFT_EXPIRED = 0x0D07
    const val ACCOUNT_CHANGED = 0x0E01
    const val RATE_LIMITS_CHANGED = 0x0E02
    const val TOKEN_USAGE_CHANGED = 0x0E03
    const val CONTEXT_USAGE_CHANGED = 0x0E04
    const val CONTEXT_COMPACTION_STARTED = 0x0E05
    const val CONTEXT_COMPACTION_COMPLETED = 0x0E06
    const val CONTEXT_COMPACTION_FAILED = 0x0E07
    const val ACTIVE_THREAD_CHANGED = 0x0E08
    const val ACTIVE_TURN_CHANGED = 0x0E09
    const val EVENTS_SINCE_RESULT = 0x0E0A
    const val RESUME_REQUIRED = 0x0E0B
    const val SEARCH_RESULT = 0x0E0C
    const val MODEL_CATALOG_CHANGED = 0x0E0D
    const val PROJECT_CATALOG_CHANGED = 0x0E0E
}

object CbdpApprovalDecision {
    const val ACCEPT = 0
    const val ACCEPT_FOR_SESSION = 1
    const val DECLINE = 2
    const val CANCEL = 3
}

object CbdpApprovalKind {
    const val COMMAND_EXECUTION = 0
    const val FILE_CHANGE = 1
    const val NETWORK_ACCESS = 2
    const val PERMISSION = 3
    const val MCP_TOOL = 4
    const val USER_INPUT = 5
    const val OTHER = 6
    const val MCP_ELICITATION = 7
}

object CbdpResyncReason {
    const val GAP = 0
    const val STALE_CURSOR = 1
    const val REVISION_MISMATCH = 2
    const val REF_GENERATION = 3
    const val POLICY_CHANGE = 4
    const val MANUAL = 5
}

object CbdpCapabilities {
    val FULL_MOBILE: Long =
        (1L shl 0) or
            (1L shl 1) or
            (1L shl 2) or
            (1L shl 3) or
            (1L shl 4) or
            (1L shl 7) or
            (1L shl 8) or
            (1L shl 9) or
            (1L shl 10) or
            (1L shl 11) or
            (1L shl 12) or
            (1L shl 13) or
            (1L shl 14) or
            (1L shl 15) or
            (1L shl 17) or
            (1L shl 19) or
            (1L shl 20) or
            (1L shl 21) or
            (1L shl 22) or
            (1L shl 23) or
            (1L shl 27) or
            (1L shl 32) or
            (1L shl 33) or
            (1L shl 34) or
            (1L shl 35) or
            (1L shl 37) or
            (1L shl 38) or
            (1L shl 39) or
            (1L shl 40) or
            (1L shl 41) or
            (1L shl 42) or
            (1L shl 43) or
            (1L shl 45) or
            (1L shl 46) or
            (1L shl 47) or
            (1L shl 48) or
            (1L shl 49) or
            (1L shl 50) or
            (1L shl 52) or
            (1L shl 54) or
            (1L shl 55) or
            (1L shl 57) or
            (1L shl 58) or
            (1L shl 59) or
            (1L shl 61)
}

class CbdpSessionException(message: String, cause: Throwable? = null) : Exception(message, cause)
