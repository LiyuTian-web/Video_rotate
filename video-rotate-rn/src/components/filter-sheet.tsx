import { BottomSheet, Button, Chip } from "heroui-native";
import type { JSX } from "react";
import { useState } from "react";
import { Text, View } from "react-native";
import { useSafeAreaInsets } from "react-native-safe-area-context";

import { pickFolder } from "../../modules/video-rotate";
import type { Angle, FilterConfig, FormatFilter, SortOrder } from "../lib/video";
import { formatLabels, sortLabels } from "../lib/video";

export function decodeTreeDisplayName(treeUri: string): string {
  const last = treeUri.split("/").pop() ?? treeUri;
  const decoded = decodeURIComponent(last);
  const tail = decoded.includes(":") ? decoded.split(":").pop() ?? "" : decoded;
  return tail.replace(/\//g, " / ") || decoded;
}

interface ChoiceProps {
  label: string;
  selected: boolean;
  onPress: () => void;
  disabled?: boolean;
}

function Choice({ label, selected, onPress, disabled }: ChoiceProps): JSX.Element {
  return (
    <Chip
      size="md"
      variant={selected ? "primary" : "secondary"}
      color="accent"
      disabled={disabled}
      onPress={onPress}
    >
      <Chip.Label>{label}</Chip.Label>
    </Chip>
  );
}

function Section({ title, children }: { title: string; children: React.ReactNode }): JSX.Element {
  return (
    <View className="mt-4 gap-2">
      <Text className="text-xs font-semibold uppercase text-muted">{title}</Text>
      {children}
    </View>
  );
}

interface FilterSheetProps {
  open: boolean;
  initial: FilterConfig;
  onClose: () => void;
  onApply: (next: FilterConfig) => void;
}

export function FilterSheet({ open, initial, onClose, onApply }: FilterSheetProps): JSX.Element {
  return (
    <BottomSheet isOpen={open} onOpenChange={(value) => !value && onClose()}>
      <BottomSheet.Portal>
        <BottomSheet.Overlay />
        {/* 不设置 snapPoints：面板高度随内容自适应，按钮紧跟在内容之后 */}
        <BottomSheet.Content enableOverDrag={false}>
          {/* BottomSheet 必须常驻挂载，isOpen 由 false→true 才会触发展开动画；
              用 key 让草稿在每次打开时以最新配置重建。 */}
          <FilterSheetBody
            key={open ? "open" : "closed"}
            initial={initial}
            onClose={onClose}
            onApply={onApply}
          />
        </BottomSheet.Content>
      </BottomSheet.Portal>
    </BottomSheet>
  );
}

function FilterSheetBody({
  initial,
  onClose,
  onApply,
}: {
  initial: FilterConfig;
  onClose: () => void;
  onApply: (next: FilterConfig) => void;
}): JSX.Element {
  const [draft, setDraft] = useState<FilterConfig>(initial);
  const insets = useSafeAreaInsets();

  const patch = (part: Partial<FilterConfig>): void => {
    setDraft((previous) => ({ ...previous, ...part }));
  };

  const pickSource = async (): Promise<void> => {
    const uri = await pickFolder(draft.sourceTreeUri ?? draft.customOutputTreeUri);
    if (uri != null) {
      patch({ sourceTreeUri: uri, sourceDisplayName: decodeTreeDisplayName(uri) });
    }
  };

  const pickOutput = async (): Promise<void> => {
    const uri = await pickFolder(draft.customOutputTreeUri ?? draft.sourceTreeUri);
    if (uri != null) {
      patch({ customOutputTreeUri: uri, customOutputDisplayName: decodeTreeDisplayName(uri) });
    }
  };

  const folderReady = draft.mode !== "folder" || draft.sourceTreeUri != null;

  const outputText =
    draft.customOutputTreeUri != null
      ? draft.customOutputDisplayName || "已选择自定义目录"
      : draft.mode === "library"
        ? "默认：每个原目录内的 rotate 文件夹"
        : `默认：${draft.sourceDisplayName}/rotate`;

  return (
    <View className="px-5" style={{ paddingBottom: Math.max(insets.bottom + 4, 20) }}>
      <Text className="pt-2 text-lg font-bold text-surface-foreground">筛选与旋转设置</Text>

          <Section title="来源">
            <View className="flex-row gap-2">
              <Choice
                label="视频库"
                selected={draft.mode === "library"}
                onPress={() => patch({ mode: "library" })}
              />
              <Choice
                label="文件夹"
                selected={draft.mode === "folder"}
                onPress={() => patch({ mode: "folder" })}
              />
            </View>
            {draft.mode === "folder" && (
              <View className="flex-row items-center gap-3">
                <View className="flex-1">
                  <Text numberOfLines={1} className="text-sm text-surface-foreground">
                    {draft.sourceDisplayName}
                  </Text>
                </View>
                <Button size="sm" variant="secondary" onPress={pickSource}>
                  选择文件夹
                </Button>
              </View>
            )}
            {draft.mode === "folder" && draft.sourceTreeUri == null && (
              <Text className="text-xs text-danger">请先选择有效的源文件夹</Text>
            )}
          </Section>

          <Section title="格式">
            <View className="flex-row gap-2">
              {(["all", "mov", "mp4"] as FormatFilter[]).map((format) => (
                <Choice
                  key={format}
                  label={formatLabels[format]}
                  selected={draft.format === format}
                  onPress={() => patch({ format })}
                />
              ))}
            </View>
          </Section>

          <Section title="排序">
            <View className="flex-row gap-2">
              {(["newest", "oldest", "name"] as SortOrder[]).map((sort) => (
                <Choice
                  key={sort}
                  label={sortLabels[sort]}
                  selected={draft.sort === sort}
                  onPress={() => patch({ sort })}
                />
              ))}
            </View>
          </Section>

          <Section title="旋转角度">
            <View className="flex-row gap-2">
              {([90, 180, 270] as Angle[]).map((angle) => (
                <Choice
                  key={angle}
                  label={`${angle}°`}
                  selected={draft.angle === angle}
                  onPress={() => patch({ angle })}
                />
              ))}
            </View>
          </Section>

          <Section title="输出位置">
            <View className="flex-row items-center gap-3">
              <Text numberOfLines={2} className="flex-1 text-sm text-surface-foreground">
                {outputText}
              </Text>
              {draft.customOutputTreeUri != null && (
                <Button
                  size="sm"
                  variant="tertiary"
                  onPress={() => patch({ customOutputTreeUri: null, customOutputDisplayName: "" })}
                >
                  恢复默认
                </Button>
              )}
              <Button size="sm" variant="secondary" onPress={pickOutput}>
                更改
              </Button>
            </View>
          </Section>

      <View className="mt-5 flex-row gap-3 border-t border-separator pt-4">
        <Button className="flex-1" variant="tertiary" onPress={onClose}>
          取消
        </Button>
        <Button className="flex-1" isDisabled={!folderReady} onPress={() => onApply(draft)}>
          完成
        </Button>
      </View>
    </View>
  );
}
