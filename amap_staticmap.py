"""输入高德 Web 服务 Key 和经纬度，生成静态地图链接（不发送请求）。"""

import math
from urllib.parse import urlencode


def build_map_url(key: str, longitude: float, latitude: float) -> str:
    """坐标顺序为经度、纬度；输入应为高德地图坐标（GCJ-02）。"""
    key = key.strip()
    if not key:
        raise ValueError("Key 不能为空")
    if not math.isfinite(longitude) or not -180 <= longitude <= 180:
        raise ValueError("经度必须在 -180 到 180 之间，请输入度数而非 JT808 原始整数")
    if not math.isfinite(latitude) or not -90 <= latitude <= 90:
        raise ValueError("纬度必须在 -90 到 90 之间，请输入度数而非 JT808 原始整数")

    location = f"{longitude:.6f},{latitude:.6f}"
    params = {
        "location": location,
        "zoom": 16,
        "size": "750*300",
        "markers": f"mid,,A:{location}",
        "key": key,
    }
    return "https://restapi.amap.com/v3/staticmap?" + urlencode(params, safe=",:*")


def main() -> int:
    print("生成高德静态地图链接（仅输出链接）。")
    print("请使用高德 Web 服务 Key；坐标应为 GCJ-02，原始 GPS 坐标需先转换。")
    try:
        key = input("请输入高德 Key：").strip()
        longitude = float(input("请输入经度，例如 113.884841：").strip())
        latitude = float(input("请输入纬度，例如 22.765470：").strip())
        url = build_map_url(key, longitude, latitude)
    except ValueError as exc:
        print(f"输入有误：{exc}")
        return 1
    except (EOFError, KeyboardInterrupt):
        print("\n已取消。")
        return 1

    print("\n复制以下链接到浏览器打开：")
    print(url)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
