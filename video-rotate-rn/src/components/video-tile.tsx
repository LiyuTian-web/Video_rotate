import { Card } from "heroui-native";
import type { JSX } from "react";
import { useEffect, useState } from "react";
import { Image, Pressable, Text, View } from "react-native";

import { getThumbnail } from "../../modules/video-rotate";
import type { VideoItem } from "../../modules/video-rotate";
import { formatDuration, formatDate } from "../lib/video";

const thumbnailCache = new Map<string, string | null>();

interface VideoTileProps {
  video: VideoItem;
  selected: boolean;
  enabled: boolean;
  onToggle: (uri: string) => void;
}

export function VideoTile({ video, selected, enabled, onToggle }: VideoTileProps): JSX.Element {
  const [thumbnail, setThumbnail] = useState<string | null | undefined>(() =>
    thumbnailCache.get(video.uri)
  );

  useEffect(() => {
    if (thumbnailCache.has(video.uri)) return;
    let active = true;
    getThumbnail(video.uri, 340, 240)
      .then((path) => {
        thumbnailCache.set(video.uri, path);
        if (active) setThumbnail(path);
      })
      .catch(() => {
        thumbnailCache.set(video.uri, null);
        if (active) setThumbnail(null);
      });
    return () => {
      active = false;
    };
  }, [video.uri]);

  return (
    <Pressable
      disabled={!enabled}
      onPress={() => onToggle(video.uri)}
      className="flex-1 active:opacity-80"
    >
      <Card className="overflow-hidden p-0">
        <View className="aspect-[17/12] items-center justify-center overflow-hidden bg-surface-secondary">
          {thumbnail != null ? (
            <Image source={{ uri: thumbnail }} resizeMode="cover" className="h-full w-full" />
          ) : (
            <Text className="text-2xl text-muted">{thumbnail === null ? "▶" : "…"}</Text>
          )}
          <View
            className={`absolute right-2 top-2 h-6 w-6 items-center justify-center rounded-full ${
              selected ? "bg-accent" : "bg-black/45"
            }`}
          >
            {selected && <Text className="text-xs font-bold text-accent-foreground">✓</Text>}
          </View>
          {video.durationMs > 0 && (
            <View className="absolute bottom-2 right-2 rounded bg-black/65 px-1 py-0.5">
              <Text className="text-[10px] text-white">{formatDuration(video.durationMs)}</Text>
            </View>
          )}
        </View>
        <View className="gap-0.5 px-2.5 py-2">
          <Text numberOfLines={1} className="text-xs font-medium text-surface-foreground">
            {video.name}
          </Text>
          {video.modifiedSeconds > 0 && (
            <Text className="text-[10px] text-muted">{formatDate(video.modifiedSeconds)}</Text>
          )}
        </View>
      </Card>
    </Pressable>
  );
}
