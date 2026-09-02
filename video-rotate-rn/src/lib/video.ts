import type { JobInput, VideoItem } from "../../modules/video-rotate";

export type SourceMode = "library" | "folder";
export type FormatFilter = "all" | "mov" | "mp4";
export type SortOrder = "newest" | "oldest" | "name";
export type Angle = 90 | 180 | 270;

export interface FilterConfig {
  mode: SourceMode;
  format: FormatFilter;
  sort: SortOrder;
  angle: Angle;
  sourceTreeUri: string | null;
  sourceDisplayName: string;
  customOutputTreeUri: string | null;
  customOutputDisplayName: string;
}

export const defaultFilter = (): FilterConfig => ({
  mode: "library",
  format: "all",
  sort: "newest",
  angle: 270,
  sourceTreeUri: null,
  sourceDisplayName: "尚未选择",
  customOutputTreeUri: null,
  customOutputDisplayName: "",
});

export const formatLabels: Record<FormatFilter, string> = {
  all: "全部格式",
  mov: "MOV",
  mp4: "MP4",
};

export const sortLabels: Record<SortOrder, string> = {
  newest: "最新",
  oldest: "最早",
  name: "名称",
};

export function filterSummary(config: FilterConfig): string {
  const source =
    config.mode === "library" ? "视频库" : `文件夹：${config.sourceDisplayName || "尚未选择"}`;
  const output =
    config.customOutputTreeUri == null
      ? "默认输出"
      : `输出：${config.customOutputDisplayName || "自定义目录"}`;
  return `${source} · ${formatLabels[config.format]} · ${sortLabels[config.sort]} · ${config.angle}° · ${output}`;
}

export function shouldClearSelection(previous: FilterConfig, next: FilterConfig): boolean {
  return previous.mode !== next.mode || previous.format !== next.format;
}

export function visibleItems(
  items: VideoItem[],
  format: FormatFilter,
  sort: SortOrder
): VideoItem[] {
  const filtered = items.filter((item) => {
    if (format === "all") return true;
    if (format === "mov") return item.name.toLowerCase().endsWith(".mov");
    return item.name.toLowerCase().endsWith(".mp4");
  });
  switch (sort) {
    case "newest":
      return [...filtered].sort(
        (a, b) => b.modifiedSeconds - a.modifiedSeconds || compareUri(b, a)
      );
    case "oldest":
      return [...filtered].sort(
        (a, b) => a.modifiedSeconds - b.modifiedSeconds || compareUri(a, b)
      );
    case "name":
      return [...filtered].sort((a, b) =>
        a.name.localeCompare(b.name, undefined, { sensitivity: "base" })
      );
  }
}

function compareUri(a: VideoItem, b: VideoItem): number {
  return a.uri < b.uri ? -1 : a.uri > b.uri ? 1 : 0;
}

const ALLOWED_VIDEO_ROOTS = ["dcim", "movies", "pictures"];

export function requiresRedirect(relativePath: string | null): boolean {
  const segments = (relativePath ?? "")
    .replace(/\\/g, "/")
    .split("/")
    .map((segment) => segment.trim())
    .filter((segment) => segment.length > 0 && segment !== "." && segment !== "..");
  const first = segments[0]?.toLowerCase();
  return first == null || !ALLOWED_VIDEO_ROOTS.includes(first);
}

export function formatDuration(milliseconds: number): string {
  const totalSeconds = Math.floor(milliseconds / 1000);
  const minutes = Math.floor(totalSeconds / 60);
  const seconds = totalSeconds % 60;
  return `${minutes}:${seconds.toString().padStart(2, "0")}`;
}

export function formatDate(seconds: number): string {
  const date = new Date(seconds * 1000);
  const month = (date.getMonth() + 1).toString().padStart(2, "0");
  const day = date.getDate().toString().padStart(2, "0");
  const hours = date.getHours().toString().padStart(2, "0");
  const minutes = date.getMinutes().toString().padStart(2, "0");
  return `${month}-${day} ${hours}:${minutes}`;
}

export function formatBytes(bytes: number): string {
  if (bytes >= 1024 * 1024 * 1024) return `${(bytes / 1024 / 1024 / 1024).toFixed(1)}GB`;
  if (bytes >= 1024 * 1024) return `${(bytes / 1024 / 1024).toFixed(1)}MB`;
  return `${Math.max(1, Math.round(bytes / 1024))}KB`;
}

export function jobInputFromVideo(video: VideoItem): JobInput {
  return {
    sourceUri: video.uri,
    displayName: video.name,
    mimeType: video.mimeType,
    size: video.size,
    relativePath: video.relativePath,
    volumeName: video.volumeName,
    sourceTreeUri: video.sourceTreeUri,
    legacyDataPath: video.legacyDataPath,
  };
}
