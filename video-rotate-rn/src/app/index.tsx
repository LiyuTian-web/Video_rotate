import Ionicons from "@expo/vector-icons/Ionicons";
import { useRouter } from "expo-router";
import { Button, Spinner, useToast } from "heroui-native";
import type { JSX } from "react";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { Dimensions, FlatList, Platform, Text, View } from "react-native";
import { useSafeAreaInsets } from "react-native-safe-area-context";
import { useCSSVariable } from "uniwind";

import {
  cancelJob,
  getPermissionState,
  requestImagePermissions,
  requestMediaPermissions,
  requestNotificationPermission,
  scanFolder,
  scanLibrary,
  startJob,
} from "../../modules/video-rotate";
import type { JobState, PermissionState, VideoItem } from "../../modules/video-rotate";
import { FilterSheet, decodeTreeDisplayName } from "../components/filter-sheet";
import { JobPanel } from "../components/job-panel";
import { NoticeCard } from "../components/notice-card";
import { VideoTile } from "../components/video-tile";
import { useJobState, usePermissionsChanged } from "../hooks/use-job-state";
import type { FilterConfig } from "../lib/video";
import {
  defaultFilter,
  filterSummary,
  jobInputFromVideo,
  requiresRedirect,
  shouldClearSelection,
  visibleItems,
} from "../lib/video";

const numColumns = Math.max(2, Math.min(4, Math.floor(Dimensions.get("window").width / 200)));

export default function HomeScreen(): JSX.Element {
  const router = useRouter();
  const insets = useSafeAreaInsets();
  const { toast } = useToast();
  const foreground = useCSSVariable("--foreground");

  const [config, setConfig] = useState<FilterConfig>(defaultFilter);
  const [sheetOpen, setSheetOpen] = useState(false);
  const [permission, setPermission] = useState<PermissionState | null>(null);
  const [library, setLibrary] = useState<VideoItem[]>([]);
  const [folder, setFolder] = useState<VideoItem[]>([]);
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [hiddenMotionPhotoCount, setHiddenMotionPhotoCount] = useState(0);
  const [scanning, setScanning] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const [preparing, setPreparing] = useState(false);
  const [dismissed, setDismissed] = useState(false);

  const job = useJobState();
  const scannedOnceRef = useRef(false);
  const finishedNotifiedRef = useRef(false);
  const selectedRef = useRef(selected);
  useEffect(() => {
    selectedRef.current = selected;
  }, [selected]);

  const refreshLibrary = useCallback(async (): Promise<void> => {
    setScanning(true);
    setMessage(null);
    try {
      const result = await scanLibrary();
      setLibrary(result.videos);
      const validUris = new Set(result.videos.map((video) => video.uri));
      setSelected(new Set([...selectedRef.current].filter((uri) => validUris.has(uri))));
      setHiddenMotionPhotoCount(result.hiddenMotionPhotoCount);
      if (result.videos.length === 0) setMessage("当前授权范围内没有 MOV 或 MP4 视频");
    } catch (error) {
      setMessage(error instanceof Error ? error.message : "读取视频库失败");
    } finally {
      setScanning(false);
    }
  }, []);

  const scanFolderInto = useCallback(async (treeUri: string): Promise<void> => {
    setScanning(true);
    setMessage(null);
    try {
      const result = await scanFolder(treeUri);
      setFolder(result.videos);
      setSelected(new Set(result.videos.map((item) => item.uri)));
      setConfig((current) => ({
        ...current,
        sourceDisplayName: result.folderName || decodeTreeDisplayName(treeUri),
      }));
      if (result.videos.length === 0) setMessage("当前目录没有 MOV 或 MP4 文件");
    } catch (error) {
      setMessage(error instanceof Error ? error.message : "目录扫描失败");
    } finally {
      setScanning(false);
    }
  }, []);

  const loadPermission = useCallback(async (): Promise<PermissionState> => {
    const state = await getPermissionState();
    setPermission(state);
    return state;
  }, []);

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect
    void loadPermission();
  }, [loadPermission]);

  usePermissionsChanged(
    useCallback(() => {
      void loadPermission().then((state) => {
        if (state.media !== "denied" && !scannedOnceRef.current) {
          scannedOnceRef.current = true;
          void refreshLibrary();
        }
      });
    }, [loadPermission, refreshLibrary])
  );

  const initialFlowDoneRef = useRef(false);
  useEffect(() => {
    if (permission == null || initialFlowDoneRef.current) return;
    initialFlowDoneRef.current = true;
    if (permission.media === "denied") {
      void requestMediaPermissions();
    } else {
      scannedOnceRef.current = true;
      // eslint-disable-next-line react-hooks/set-state-in-effect
      void refreshLibrary();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [permission]);

  const items = config.mode === "library" ? library : folder;
  const displayed = useMemo(
    () => visibleItems(items, config.format, config.sort),
    [items, config.format, config.sort]
  );
  const selectedCount = displayed.filter((video) => selected.has(video.uri)).length;
  const running = job?.kind === "running" || preparing;
  const redirectedCount =
    Platform.OS === "android" &&
    Number(Platform.Version) >= 29 &&
    config.mode === "library" &&
    config.customOutputTreeUri == null
      ? displayed.filter((video) => selected.has(video.uri) && requiresRedirect(video.relativePath))
          .length
      : 0;

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect
    if (job?.kind === "running") setPreparing(false);
    if (job?.kind !== "finished") {
      finishedNotifiedRef.current = false;
      return;
    }
    if (finishedNotifiedRef.current) return;
    finishedNotifiedRef.current = true;
    setPreparing(false);
    toast.show({
      variant: job.failedFiles > 0 || job.cancelled ? "warning" : "success",
      label: job.cancelled ? "任务已取消" : "处理完成",
      description: `成功 ${job.completedFiles} 个，失败 ${job.failedFiles} 个`,
    });
    if (!job.cancelled && job.completedFiles > 0 && config.mode === "library") {
      void refreshLibrary();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [job]);

  const toggle = (uri: string): void => {
    setSelected((current) => {
      const next = new Set(current);
      if (next.has(uri)) next.delete(uri);
      else next.add(uri);
      return next;
    });
  };

  const selectAll = (flag: boolean): void => {
    const visibleUris = displayed.map((video) => video.uri);
    setSelected((current) => {
      const next = new Set(current);
      visibleUris.forEach((uri) => (flag ? next.add(uri) : next.delete(uri)));
      return next;
    });
  };

  const applyFilter = (next: FilterConfig): void => {
    if (shouldClearSelection(config, next)) setSelected(new Set());
    const sourceChanged = config.mode !== next.mode || config.sourceTreeUri !== next.sourceTreeUri;
    const wasRunning = running;
    setConfig(next);
    setSheetOpen(false);
    setMessage(null);
    if (wasRunning) return;
    if (next.mode === "library") {
      if (permission != null && permission.media !== "denied") void refreshLibrary();
      else void requestMediaPermissions();
    } else if (next.sourceTreeUri != null && (sourceChanged || folder.length === 0)) {
      void scanFolderInto(next.sourceTreeUri);
    }
  };

  const start = async (): Promise<void> => {
    const chosen = displayed.filter((video) => selected.has(video.uri));
    if (chosen.length === 0) {
      setMessage("请至少选择一个视频");
      return;
    }
    if (running) {
      setMessage("已有任务正在运行");
      return;
    }
    if (permission != null && !permission.notifications) {
      void requestNotificationPermission();
    }
    setPreparing(true);
    setDismissed(false);
    setMessage(null);
    try {
      await startJob(chosen.map(jobInputFromVideo), config.angle, config.customOutputTreeUri);
    } catch (error) {
      setPreparing(false);
      setMessage(error instanceof Error ? error.message : "无法创建任务");
    }
  };

  const finishState: JobState | null = job;

  return (
    <View
      className="flex-1 bg-background"
      style={{ paddingTop: insets.top + 10, paddingBottom: insets.bottom + 10 }}
    >
      <View className="flex-1 px-4">
        <View className="flex-row items-center justify-between pb-3">
          <View className="flex-1">
            <Text className="text-2xl font-bold text-foreground">无损视频旋转</Text>
            <Text className="text-xs text-muted">只修改播放方向，不重新编码视频</Text>
          </View>
          <Button size="sm" variant="tertiary" aria-label="设置" onPress={() => router.push("/about")}>
            <Ionicons
              name="settings-outline"
              size={22}
              color={typeof foreground === "string" ? foreground : "#000"}
            />
          </Button>
        </View>

        <Button
          variant="secondary"
          size="lg"
          isDisabled={running}
          onPress={() => setSheetOpen(true)}
          className="mb-3 w-full justify-between"
        >
          <Text className="text-sm font-semibold text-secondary-foreground">筛选</Text>
          <Text numberOfLines={1} className="max-w-[68%] text-xs text-secondary-foreground">
            {filterSummary(config)}
          </Text>
        </Button>

        <View className="flex-1 gap-3">
          {config.mode === "library" && permission != null && permission.media === "denied" && (
            <NoticeCard
              title="允许读取视频"
              description="授权后可按目录浏览视频库缩略图，拒绝仍可使用文件夹模式"
              actionLabel="授权"
              onAction={() => void requestMediaPermissions()}
            />
          )}
          {config.mode === "library" &&
            permission != null &&
            permission.media !== "denied" &&
            !permission.images && (
              <NoticeCard
                title="排除动态照片"
                description="需要读取照片名称，仅用于匹配同名的短视频片段"
                actionLabel="授权"
                onAction={() => void requestImagePermissions()}
              />
            )}
          {config.mode === "library" && permission?.media === "partial" && (
            <NoticeCard
              title="当前只显示已授权的视频"
              actionLabel="管理范围"
              onAction={() => void requestMediaPermissions()}
            />
          )}
          {redirectedCount > 0 && (
            <NoticeCard
              title={`其中 ${redirectedCount} 个视频位于系统限制写入的目录`}
              description="将自动保存到 Movies/无损视频旋转/原文件夹/rotate；如需其他位置请在筛选中更改输出目录"
            />
          )}

          <View className="flex-row items-center gap-2">
            <View className="flex-1">
              <Text className="text-sm font-semibold text-foreground">
                视频 {selectedCount}/{displayed.length}
              </Text>
              {hiddenMotionPhotoCount > 0 && (
                <Text className="text-xs text-accent">
                  已隐藏动态照片 {hiddenMotionPhotoCount} 个
                </Text>
              )}
            </View>
            {config.mode === "library" && (
              <Button
                size="sm"
                variant="tertiary"
                isDisabled={running}
                onPress={() => void refreshLibrary()}
              >
                刷新
              </Button>
            )}
            <Button
              size="sm"
              variant="tertiary"
              isDisabled={displayed.length === 0 || running}
              onPress={() => selectAll(true)}
            >
              全选
            </Button>
            <Button
              size="sm"
              variant="tertiary"
              isDisabled={displayed.length === 0 || running}
              onPress={() => selectAll(false)}
            >
              清空
            </Button>
            {scanning && <Spinner size="sm" />}
          </View>

          {message != null && <Text className="text-xs text-danger">{message}</Text>}

          {displayed.length === 0 && !scanning ? (
            <View className="flex-1 items-center justify-center">
              <Text className="text-sm text-muted">
                {config.mode === "library" && permission?.media === "denied"
                  ? "授权后这里会显示手机中的视频"
                  : "没有可显示的视频"}
              </Text>
            </View>
          ) : (
            <FlatList
              data={displayed}
              keyExtractor={(video) => video.uri}
              numColumns={numColumns}
              columnWrapperStyle={{ gap: 8 }}
              contentContainerStyle={{ gap: 8, paddingBottom: 8 }}
              showsVerticalScrollIndicator={false}
              renderItem={({ item }) => (
                <View style={{ flex: 1 }}>
                  <VideoTile
                    video={item}
                    selected={selected.has(item.uri)}
                    enabled={!running}
                    onToggle={toggle}
                  />
                </View>
              )}
            />
          )}

          {!dismissed && (
            <JobPanel
              state={finishState}
              preparing={preparing && job?.kind !== "running"}
              onCancel={() => void cancelJob()}
              onDismiss={() => setDismissed(true)}
            />
          )}
        </View>

        <Button
          size="lg"
          isDisabled={selectedCount === 0 || running}
          onPress={() => void start()}
          className="mt-3 w-full"
        >
          {running ? "正在处理…" : "开始无损旋转"}
        </Button>
      </View>

      <FilterSheet
        open={sheetOpen}
        initial={config}
        onClose={() => setSheetOpen(false)}
        onApply={(next) => void applyFilter(next)}
      />
    </View>
  );
}
