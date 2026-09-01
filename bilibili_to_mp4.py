"""
20231023 v0.1
    还原 Redmi Note 11 5G\内部存储设备\Android\data\tv.danmaku.bili\download 下载的视频
20231030 v0.2
    缺少json文件的异常处理


todo：暂时开发的apk无法进入 手机\内部存储设备\Android\data\

"""
import os
import re
import json
import subprocess
import shutil


# 查找视频目录
def find_folder_recursive(base_path):
    '''
    查找[c_\d{8,10}]文件夹并且返回所有对应的文件路径
    :param base_path: 包含所有视频的目录
    :return:
    '''
    pattern = r"c_\d{8,10}"  # 使用正则表达式匹配以 c_x 命名的文件夹（x 是 10 个数字）
    matching_folders = []

    for root, dirs, files in os.walk(base_path):
        for folder_name in dirs:
            if re.match(pattern, folder_name):
                matching_folder = os.path.join(root, folder_name)
                matching_folders.append(matching_folder)
    return matching_folders


def read_entry_data(folder_path):
    '''
    返回系列视频的名称和这一集的名称，如果只有一集就返回两个一样的系列视频的名称
    :param folder_path:
    :return:
    '''
    json_file = os.path.join(folder_path, "entry.json")
    with open(json_file, 'r', encoding="utf8") as f:
        data = json.load(f)
        try:
            part_value = data['page_data']['part']
            return data['title'], part_value
        except KeyError:
            part_value = data['title']
            return data['title'], part_value


def merge_videos(folder_path, part_value):
    """
    合并[c_\d{8,10}]文件夹下（子目录下）视频并且重命名后放到合并[c_\d{8,10}]文件夹下
    :param folder_path: 包含80,64等文件夹的文件夹，也就是[c_\d{8,10}]文件夹
    :param part_value:
    :return:
    """
    output_file = os.path.join(os.path.dirname(folder_path), f"{part_value}.mp4")

    video_subdirs = ["80", "64"]
    for video_subdir in video_subdirs:
        if os.path.exists(os.path.join(folder_path, video_subdir)):
            audio_file = os.path.join(folder_path, video_subdir, "audio.m4s")
            video_file = os.path.join(folder_path, video_subdir, "video.m4s")
            break
    if os.path.exists(output_file):
        os.remove(output_file)

    # 没有安装ffmpeg提示，FileNotFoundError: [WinError 2] 系统找不到指定的文件。
    # 但是dir也会提示相同的错误，原因是dir是（CMD）的内建命令，而不是可执行文件
    # subprocess.run(['dir'])
    subprocess.run(['ffmpeg', '-i', audio_file, '-i', video_file, '-c', 'copy', output_file])


# 删除文件夹
def rm_dir(dir_to_remove):
    shutil.rmtree(dir_to_remove)


# windows文件名称格式化
def format_windows_filename(string):
    # 定义正则表达式模式，匹配不符合要求的字符
    pattern = r'[<>:"/\\|?*\x00-\x1F\x7F]'
    # 使用正则表达式替换不符合要求的字符为XXX
    formatted_string = re.sub(pattern, '', string)

    return formatted_string



if __name__ == '__main__':


    base_path = r"path\to\bilibili_cache"  # 改成你的 B 站缓存目录（需要绝对路径）

    matching_folders = find_folder_recursive(base_path)
    pre_dir = {"path": "", "title": ""}
    this_dir = {"path": "", "title": ""}
    # json_not_found = False

    if matching_folders:
        for folder_path in matching_folders:
            # 获取视频名称
            # print()
            # print("1:",folder_path)

            this_dir["path"] = os.path.dirname(folder_path)
            try:
                title_or_part, part_value = read_entry_data(folder_path)
            except FileNotFoundError as e:
                print(e)
                # json_not_found = True
                title_or_part = None
                part_value = None

                # 不是第一个处理文件
                if pre_dir["path"]:
                    # 不是同一个系列
                    if this_dir["path"] != pre_dir["path"]:
                        this_dir = {"path": "", "title": ""}
                    # 同一个系列，
                    else:
                        # 删除这个文件夹
                        rm_dir(folder_path)
                        continue

            if title_or_part:
                this_dir["title"] = title_or_part
            print("2:", folder_path,"(",title_or_part,")")
            # 合并视频
            if part_value:
                merge_videos(folder_path, part_value)
            # 删除原始视频音频数据
            rm_dir(folder_path)
            # 重命名视频文件夹
            # pre_dir不存在
            if not pre_dir["path"]:
                # print('''pre_dir["path"] 不存在''')
                pre_dir["path"] = this_dir["path"]
                pre_dir["title"] = this_dir["title"]
            # pre_dir存在
            elif pre_dir["path"] and this_dir["path"] != pre_dir["path"]:
                # print('''pre_dir["path"] 存在: ''',pre_dir["path"])
                print("pre_dir改名：")
                print('\tpre_dir["path"]:',pre_dir["path"])
                print('\t->:',os.path.join(os.path.dirname(pre_dir["path"]), pre_dir["title"]))

                filename = format_windows_filename(pre_dir["title"])
                os.rename(pre_dir["path"],
                          os.path.join(os.path.dirname(pre_dir["path"]), filename))

                pre_dir["path"] = this_dir["path"]
                pre_dir["title"] = this_dir["title"]
            # 同一个系列
            else:
                print("和上一个视频同一个系列: ")
                print("\tpre_dir: ", pre_dir)
                print("\tthis_dir: ", this_dir)

            print("这轮结束： ")
            print("\tpre_dir: ",pre_dir)

        # 处理最后一个this_dir
        print("最后一个视频：")
        if pre_dir["path"] and this_dir["path"] == pre_dir["path"]:
            # 同一个系列的视频，或者系列只有一个视频
            print("same...")
            print('\tthis_dir["path"]: ', this_dir["path"])
            print('\t->:', os.path.join(os.path.dirname(this_dir["path"]), pre_dir["title"]))
            if pre_dir["title"]:
                os.rename(this_dir["path"],
                          os.path.join(os.path.dirname(this_dir["path"]), pre_dir["title"]))
        else:
            print("not same...")
            print('\tthis_dir["path"]: ',this_dir["path"])
            print('\t->:',os.path.join(os.path.dirname(this_dir["path"]), this_dir["title"]))
            os.rename(this_dir["path"],
                      os.path.join(os.path.dirname(this_dir["path"]), this_dir["title"]))

    else:
        print("No matching folders found.")
