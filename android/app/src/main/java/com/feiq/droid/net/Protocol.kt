package com.feiq.droid.net

/**
 * IPMsg / 飞秋协议常量。
 * 与 tools/ipmsg_tool.py 中已实测验证过的常量一一对应。
 * 详见 docs/01-协议还原与安卓互联开发指南.md。
 */
object Protocol {
    const val DEFAULT_PORT = 2425
    const val BROADCAST = "255.255.255.255"

    // 命令字（低 8 位）
    const val IPMSG_NOOPERATION = 0x00000000
    const val IPMSG_BR_ENTRY    = 0x00000001  // 上线广播
    const val IPMSG_BR_EXIT     = 0x00000002  // 下线广播
    const val IPMSG_ANSENTRY    = 0x00000003  // 上线应答
    const val IPMSG_BR_ABSENCE  = 0x00000004
    const val IPMSG_GETLIST     = 0x00000012
    const val IPMSG_ANSLIST     = 0x00000013
    const val IPMSG_SENDMSG     = 0x00000020  // 发消息
    const val IPMSG_RECVMSG     = 0x00000021  // 送达回执
    const val IPMSG_READMSG     = 0x00000030
    const val IPMSG_GETINFO     = 0x00000040
    const val IPMSG_SENDINFO    = 0x00000041
    const val IPMSG_GETFILEDATA = 0x00000060  // TCP 取文件
    const val IPMSG_RELEASEFILES= 0x00000061
    const val IPMSG_GETDIRFILES = 0x00000062  // TCP 取文件夹

    // 选项位（高 24 位）
    const val IPMSG_ABSENCEOPT    = 0x00000100
    const val IPMSG_DIALUPOPT     = 0x00010000
    const val IPMSG_FILEATTACHOPT = 0x00200000  // 带文件
    const val IPMSG_ENCRYPTOPT    = 0x00400000  // 加密
    const val IPMSG_UTF8OPT       = 0x00800000  // UTF-8

    // 发消息选项
    const val IPMSG_SENDCHECKOPT  = 0x00000100  // 要求回执
    const val IPMSG_SECRETOPT     = 0x00000200
    const val IPMSG_BROADCASTOPT  = 0x00000400
    const val IPMSG_MULTICASTOPT  = 0x00000800
    const val IPMSG_NOPOPUPOPT    = 0x00001000

    // 文件属性（fileattr 低 8 位）
    const val IPMSG_FILE_REGULAR   = 0x01
    const val IPMSG_FILE_DIR       = 0x02
    const val IPMSG_FILE_RETPARENT = 0x03

    fun commandBase(cmd: Int): Int = cmd and 0xFF

    fun describe(cmd: Int): String {
        val base = cmd and 0xFF
        val name = when (base) {
            IPMSG_BR_ENTRY -> "BR_ENTRY"; IPMSG_BR_EXIT -> "BR_EXIT"
            IPMSG_ANSENTRY -> "ANSENTRY"; IPMSG_BR_ABSENCE -> "BR_ABSENCE"
            IPMSG_GETLIST -> "GETLIST"; IPMSG_ANSLIST -> "ANSLIST"
            IPMSG_SENDMSG -> "SENDMSG"; IPMSG_RECVMSG -> "RECVMSG"
            IPMSG_READMSG -> "READMSG"; IPMSG_GETINFO -> "GETINFO"
            IPMSG_GETFILEDATA -> "GETFILEDATA"; IPMSG_GETDIRFILES -> "GETDIRFILES"
            else -> "0x%02X".format(base)
        }
        val opts = buildList {
            if (cmd and IPMSG_FILEATTACHOPT != 0) add("FILE")
            if (cmd and IPMSG_ENCRYPTOPT != 0) add("ENC")
            if (cmd and IPMSG_UTF8OPT != 0) add("UTF8")
            if (base == IPMSG_SENDMSG && cmd and IPMSG_MULTICASTOPT != 0) add("MULTI")
        }
        return if (opts.isEmpty()) name else "$name[${opts.joinToString("|")}]"
    }
}
