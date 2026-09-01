import { Button, Card } from "heroui-native";
import type { JSX } from "react";
import { Text, View } from "react-native";

import type { JobState } from "../../modules/video-rotate";
import { ProgressBar } from "./progress-bar";

interface JobPanelProps {
  state: JobState | null;
  preparing: boolean;
  onCancel: () => void;
  onDismiss: () => void;
}

export function JobPanel({
  state,
  preparing,
  onCancel,
  onDismiss,
}: JobPanelProps): JSX.Element | null {
  const running = state != null && state.kind === "running";
  if (state == null && !preparing) return null;

  if (state != null && state.kind === "finished") {
    return (
      <Card className="flex-row items-center gap-3 p-3">
        <View className="flex-1 gap-0.5">
          <Text className="text-sm font-semibold text-surface-foreground">
            {state.cancelled ? "任务已取消" : "处理完成"}
          </Text>
          <Text className="text-xs text-muted">
            成功 {state.completedFiles}，失败 {state.failedFiles}
          </Text>
          {state.messages.slice(0, 2).map((message) => (
            <Text key={message} numberOfLines={1} className="text-xs text-danger">
              {message}
            </Text>
          ))}
        </View>
        <Button size="sm" variant="tertiary" onPress={onDismiss}>
          关闭
        </Button>
      </Card>
    );
  }

  const ratio =
    running && state.kind === "running" && state.fileBytesTotal > 0
      ? state.fileBytesDone / state.fileBytesTotal
      : 0;

  return (
    <Card className="gap-2 p-3">
      <Text numberOfLines={1} className="text-sm font-medium text-surface-foreground">
        {running && state.kind === "running"
          ? `${state.currentIndex}/${state.totalFiles}  ${state.fileName}`
          : "正在准备…"}
      </Text>
      <ProgressBar ratio={ratio} />
      <View className="flex-row items-center">
        <Text className="flex-1 text-xs text-muted">
          {running && state.kind === "running"
            ? `成功 ${state.completedFiles} · 失败 ${state.failedFiles}`
            : "正在创建任务"}
        </Text>
        <Button size="sm" variant="secondary" onPress={onCancel}>
          取消
        </Button>
      </View>
    </Card>
  );
}
