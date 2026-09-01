import { EventEmitter, requireNativeModule } from "expo-modules-core";

export type MediaPermissionLevel = "full" | "partial" | "denied";

export interface PermissionState {
  media: MediaPermissionLevel;
  images: boolean;
  notifications: boolean;
}

export interface VideoItem {
  uri: string;
  name: string;
  size: number;
  durationMs: number;
  modifiedSeconds: number;
  relativePath: string | null;
  volumeName: string | null;
  mimeType: string;
  legacyDataPath: string | null;
  sourceTreeUri: string | null;
}

export interface LibraryResult {
  videos: VideoItem[];
  hiddenMotionPhotoCount: number;
  motionPhotoFilteringAvailable: boolean;
}

export interface FolderResult {
  folderName: string;
  videos: VideoItem[];
}

export type JobState =
  | { kind: "idle" }
  | {
      kind: "running";
      currentIndex: number;
      totalFiles: number;
      fileName: string;
      fileBytesDone: number;
      fileBytesTotal: number;
      completedFiles: number;
      failedFiles: number;
    }
  | {
      kind: "finished";
      completedFiles: number;
      failedFiles: number;
      cancelled: boolean;
      messages: string[];
    };

export interface JobInput {
  sourceUri: string;
  displayName: string;
  mimeType: string;
  size: number;
  relativePath: string | null;
  volumeName: string | null;
  sourceTreeUri: string | null;
  legacyDataPath: string | null;
}

const native = requireNativeModule("Rotator");

interface RotatorEventEmitter {
  addListener(
    event: "onJobState",
    listener: (state: JobState) => void
  ): {
    remove: () => void;
  };
  addListener(
    event: "onPermissionsChanged",
    listener: (state: PermissionState) => void
  ): {
    remove: () => void;
  };
  removeListener(event: string, listener: (...args: unknown[]) => void): void;
}

export const rotatorEvents = new EventEmitter(native) as unknown as RotatorEventEmitter;

export const getPermissionState = (): Promise<PermissionState> => native.getPermissionState();

export const requestMediaPermissions = (): Promise<PermissionState> =>
  native.requestMediaPermissions();

export const requestImagePermissions = (): Promise<PermissionState> =>
  native.requestImagePermissions();

export const requestNotificationPermission = (): Promise<PermissionState> =>
  native.requestNotificationPermission();

export const scanLibrary = (): Promise<LibraryResult> => native.scanLibrary();

export const scanFolder = (treeUri: string): Promise<FolderResult> => native.scanFolder(treeUri);

export const pickFolder = (initialUri?: string | null): Promise<string | null> =>
  native.pickFolder(initialUri ?? null);

export const getThumbnail = (uri: string, width: number, height: number): Promise<string | null> =>
  native.getThumbnail(uri, width, height);

export const startJob = (
  inputs: JobInput[],
  angleDegrees: number,
  outputTreeUri?: string | null,
): Promise<boolean> =>
  native.startJob(JSON.stringify(inputs), angleDegrees, outputTreeUri ?? null);

export const cancelJob = (): Promise<boolean> => native.cancelJob();
