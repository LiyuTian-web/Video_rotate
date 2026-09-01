import { Button, Card } from "heroui-native";
import type { JSX } from "react";
import { Text, View } from "react-native";

interface NoticeCardProps {
  title: string;
  description?: string;
  actionLabel?: string;
  onAction?: () => void;
}

export function NoticeCard({
  title,
  description,
  actionLabel,
  onAction,
}: NoticeCardProps): JSX.Element {
  return (
    <Card className="flex-row items-center gap-3 p-3">
      <View className="flex-1 gap-0.5">
        <Text className="text-sm font-semibold text-surface-foreground">{title}</Text>
        {description != null && <Text className="text-xs text-muted">{description}</Text>}
      </View>
      {actionLabel != null && onAction != null && (
        <Button size="sm" variant="secondary" onPress={onAction}>
          {actionLabel}
        </Button>
      )}
    </Card>
  );
}
