import * as Clipboard from "expo-clipboard";
import { useRouter } from "expo-router";
import { Button, Card, Chip } from "heroui-native";
import type { JSX } from "react";
import { useEffect, useState } from "react";
import { Image, Linking, ScrollView, Text, View } from "react-native";
import { useSafeAreaInsets } from "react-native-safe-area-context";

import type { ThemeMode } from "../lib/appearance";
import { loadThemeMode, saveThemeMode } from "../lib/appearance";

const AUTHOR_EMAIL = "woshitianyumi@outlook.com";

type ThemeModeOption = { value: ThemeMode; label: string };

const themeModes: ThemeModeOption[] = [
  { value: "system", label: "跟随系统" },
  { value: "light", label: "浅色" },
  { value: "dark", label: "深色" },
];

function DonationCard({
  title,
  source,
  imageClassName,
}: {
  title: string;
  source: number;
  imageClassName: string;
}): JSX.Element {
  return (
    <Card className="items-center p-4">
      <Text className="text-base font-semibold text-surface-foreground">{title}</Text>
      <Image source={source} resizeMode="contain" className={imageClassName} />
    </Card>
  );
}

function InfoRow({ label, value }: { label: string; value: string }): JSX.Element {
  return (
    <View className="flex-row py-2">
      <Text className="w-24 text-sm font-semibold text-foreground">{label}</Text>
      <Text className="flex-1 text-sm text-foreground">{value}</Text>
    </View>
  );
}

export default function SettingsScreen(): JSX.Element {
  const router = useRouter();
  const insets = useSafeAreaInsets();
  const [message, setMessage] = useState<string | null>(null);
  const [currentMode, setCurrentMode] = useState<ThemeMode>("system");

  useEffect(() => {
    let active = true;
    void loadThemeMode().then((saved) => {
      if (active) setCurrentMode(saved);
    });
    return () => {
      active = false;
    };
  }, []);

  const selectMode = (next: ThemeMode): void => {
    setCurrentMode(next);
    void saveThemeMode(next);
  };

  const contactAuthor = async (): Promise<void> => {
    setMessage(null);
    const url = `mailto:${AUTHOR_EMAIL}`;
    try {
      if (await Linking.canOpenURL(url)) {
        await Linking.openURL(url);
        return;
      }
    } catch {
      // fall through to clipboard
    }
    await Clipboard.setStringAsync(AUTHOR_EMAIL);
    setMessage("未找到邮件应用，邮箱地址已复制到剪贴板");
  };

  return (
    <View
      className="flex-1 bg-background"
      style={{ paddingTop: insets.top + 4, paddingBottom: insets.bottom + 12 }}
    >
      <View className="flex-row items-center gap-1 px-2 py-1">
        <Button size="sm" variant="tertiary" onPress={() => router.back()}>
          返回
        </Button>
        <Text className="text-xl font-bold text-foreground">设置</Text>
      </View>
      <View className="h-px bg-separator" />
      <ScrollView
        contentContainerClassName="items-center px-5 py-6"
        showsVerticalScrollIndicator={false}
      >
        <View className="w-full max-w-[520px]">
          <Text className="text-base font-semibold text-foreground">外观模式</Text>
          <View className="mt-3 flex-row gap-2">
            {themeModes.map((item) => (
              <Chip
                key={item.value}
                size="md"
                variant={currentMode === item.value ? "primary" : "secondary"}
                color="accent"
                onPress={() => selectMode(item.value)}
              >
                <Chip.Label>{item.label}</Chip.Label>
              </Chip>
            ))}
          </View>
        </View>

        <View className="mt-8 h-px w-full max-w-[520px] bg-separator" />

        <Text className="mt-6 text-2xl font-bold text-foreground">无损视频旋转</Text>
        <Text className="mt-2 text-base text-foreground">主打无损视频旋转，拯救 NIKON 用户</Text>
        <Text className="mt-1 text-sm text-muted">V 2.0.0 · React Native 重写版</Text>

        <View className="mt-6 w-full max-w-[520px]">
          <InfoRow label="软件作者" value="顶天立宇" />
          <View className="flex-row items-center py-2">
            <Text className="w-24 text-sm font-semibold text-foreground">联系作者</Text>
            <Button size="sm" variant="tertiary" onPress={() => void contactAuthor()}>
              {AUTHOR_EMAIL}
            </Button>
          </View>
          {message != null && <Text className="text-xs text-success">{message}</Text>}
        </View>

        <Text className="mt-8 text-lg font-bold text-foreground">赞赏作者</Text>
        <Text className="mb-4 mt-1 text-base text-muted">请作者喝一杯奶茶吧！</Text>

        <View className="w-full max-w-[440px] items-center gap-4">
          <DonationCard
            title="支付宝"
            source={require("../../assets/images/donate_alipay.jpg")}
            imageClassName="mt-3 h-[300px] w-[200px]"
          />
          <DonationCard
            title="微信支付"
            source={require("../../assets/images/donate_wechat.png")}
            imageClassName="mt-3 h-[272px] w-[200px]"
          />
        </View>
      </ScrollView>
    </View>
  );
}
