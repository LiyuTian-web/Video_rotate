import { useEffect, useState } from "react";

import { rotatorEvents } from "../../modules/video-rotate";
import type { JobState } from "../../modules/video-rotate";

export function useJobState(): JobState | null {
  const [state, setState] = useState<JobState | null>(null);

  useEffect(() => {
    const subscription = rotatorEvents.addListener("onJobState", (event: JobState) => {
      setState(event);
    });
    return () => subscription.remove();
  }, []);

  return state;
}

export function usePermissionsChanged(onChange: () => void): void {
  useEffect(() => {
    const subscription = rotatorEvents.addListener("onPermissionsChanged", () => {
      onChange();
    });
    return () => subscription.remove();
  }, [onChange]);
}
