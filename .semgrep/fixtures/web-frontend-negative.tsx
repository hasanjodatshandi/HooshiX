import {bffRequest} from "../../apps/web-frontend/src/api/bffClient";

export function SafeMarkup({message}: {message: string}) {
  return <main>{message}</main>;
}

export async function useBff(path: string) {
  return bffRequest(path, {method: "GET"});
}
