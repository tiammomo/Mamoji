import client from "./client";
import type { WorkspaceView } from "@/lib/types";

export const workspaceApi = {
  view: () => client.get<WorkspaceView>("/workspace"),
};
