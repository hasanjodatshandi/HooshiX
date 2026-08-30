export function UnsafeMarkup({html}: {html: string}) {
  return <main dangerouslySetInnerHTML={{__html: html}} />;
}

export async function bypassBff(path: string) {
  return fetch(path);
}
