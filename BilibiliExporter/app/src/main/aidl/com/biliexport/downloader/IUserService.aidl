// IUserService.aidl
package com.biliexport.downloader;

interface IUserService {
    // 扫描 B 站下载目录，返回所有 c_XXXXXXXX 集数文件夹的绝对路径
    List<String> listEpisodeFolders(String basePath) = 1;

    // 读取 entry.json 内容（返回字符串，避免大对象传输）
    String readTextFile(String filePath) = 2;

    // 列出指定目录下的所有子项（子目录用 "[D] " 前缀，文件用 "[F] " 前缀）
    List<String> listDir(String dirPath) = 3;

    // 检查文件/目录是否存在
    boolean exists(String path) = 4;

    // 复制单个文件（src → dest）
    boolean copyFile(String srcPath, String destPath) = 5;

    // 递归复制目录（src 目录 → dest 目录）
    boolean copyDirectory(String srcDir, String destDir) = 6;

    // 获取文件大小（字节）
    long fileSize(String filePath) = 7;

    // 递归删除文件/目录（用于删除 shell UID 创建的文件，APP 进程无权删除时使用）
    boolean deleteRecursively(String path) = 8;

    // 获取文件/目录最后修改时间（毫秒时间戳）
    long lastModified(String path) = 9;

    // Shizuku 内置的销毁方法（transactionCode 必须为 16777114）
    void destroy() = 16777114;
}
