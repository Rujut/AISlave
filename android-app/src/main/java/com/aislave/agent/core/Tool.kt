package com.aislave.agent.core

enum class ToolRisk {
    Low,
    Medium,
    High
}

data class ToolSpec(
    val name: String,
    val description: String,
    val requiredAccess: List<String>,
    val risk: ToolRisk,
    val requiresConfirmation: Boolean,
    val canUndo: Boolean,
    val verification: String
)

object V1ToolRegistry {
    val searchFiles = ToolSpec(
        name = "search_files",
        description = "Search file names inside a user-granted folder.",
        requiredAccess = listOf("SAF read access for selected folder"),
        risk = ToolRisk.Low,
        requiresConfirmation = false,
        canUndo = false,
        verification = "Return matching files from the current folder listing."
    )

    val moveFile = ToolSpec(
        name = "move_file",
        description = "Move one user-selected file between two user-granted folders.",
        requiredAccess = listOf("SAF read/write access for source folder", "SAF read/write access for destination folder"),
        risk = ToolRisk.Medium,
        requiresConfirmation = true,
        canUndo = true,
        verification = "Destination file exists and source file no longer exists."
    )

    val copyFile = ToolSpec(
        name = "copy_file",
        description = "Copy one file into a user-granted destination folder.",
        requiredAccess = listOf("SAF read access for source file", "SAF write access for destination folder"),
        risk = ToolRisk.Low,
        requiresConfirmation = false,
        canUndo = true,
        verification = "Destination copy exists."
    )

    val renameFile = ToolSpec(
        name = "rename_file",
        description = "Rename one file inside a user-granted folder.",
        requiredAccess = listOf("SAF write access for selected file"),
        risk = ToolRisk.Medium,
        requiresConfirmation = true,
        canUndo = true,
        verification = "Renamed file exists."
    )

    val createFolder = ToolSpec(
        name = "create_folder",
        description = "Create a folder inside a user-granted parent folder.",
        requiredAccess = listOf("SAF write access for parent folder"),
        risk = ToolRisk.Low,
        requiresConfirmation = false,
        canUndo = true,
        verification = "Created folder exists and is a directory."
    )

    val deleteFile = ToolSpec(
        name = "delete_file",
        description = "Delete one user-selected file.",
        requiredAccess = listOf("SAF write access for selected file"),
        risk = ToolRisk.High,
        requiresConfirmation = true,
        canUndo = false,
        verification = "Deleted file no longer exists."
    )

    val readExcel = ToolSpec(
        name = "read_excel",
        description = "Read workbook sheets, rows, cells, and formulas.",
        requiredAccess = listOf("SAF read access for selected XLSX file"),
        risk = ToolRisk.Low,
        requiresConfirmation = false,
        canUndo = false,
        verification = "Workbook opens and parsed sheet metadata is returned."
    )

    val writeExcel = ToolSpec(
        name = "write_excel",
        description = "Write values, formulas, and formatting to an XLSX workbook.",
        requiredAccess = listOf("SAF read/write access for selected XLSX file"),
        risk = ToolRisk.Medium,
        requiresConfirmation = true,
        canUndo = true,
        verification = "Workbook reopens and changed cells match the requested output."
    )

    val createCalendarEvent = ToolSpec(
        name = "create_calendar_event",
        description = "Create a calendar event after calendar permission is granted.",
        requiredAccess = listOf("Android calendar write permission"),
        risk = ToolRisk.Medium,
        requiresConfirmation = true,
        canUndo = true,
        verification = "Created event can be read back from CalendarProvider."
    )

    val all = listOf(
        searchFiles,
        moveFile,
        copyFile,
        renameFile,
        createFolder,
        deleteFile,
        readExcel,
        writeExcel,
        createCalendarEvent
    )
}
