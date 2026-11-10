/**
 * The pure half of the MCP setup page: what a client is told to connect to
 * this instance's MCP endpoint. Kept out of the component so the snippets can
 * be checked without rendering.
 *
 * The endpoint is served by the backend itself at `/api/sql-explorer/mcp`
 * (streamable HTTP, stateless), so a client is given a URL — no install, no
 * checkout path. The tools are not listed here: the page asks the endpoint
 * (`tools/list`), so this list cannot drift from the server's.
 */

/** The path the UI reaches the backend through (nginx / the dev proxy). */
export const API_PATH = '/api/sql-explorer';
export const MCP_PATH = `${API_PATH}/mcp`;
export const ENVIRONMENT_HEADER = 'X-Sql-Explorer-Environment';

export interface McpConfig {
  /** The endpoint's URL as a client on this machine reaches it. */
  url: string;
  /** Sent as {@link ENVIRONMENT_HEADER}; null leaves it out, so the backend's default is used. */
  environment: string | null;
}

/** The endpoint, at the origin this page was served from. */
export function mcpUrl(origin: string): string {
  return origin.replace(/\/$/, '') + MCP_PATH;
}

/** Single-quoted for a POSIX shell, unless it plainly needs no quoting. */
function shellWord(value: string): string {
  return /^[\w@%+=:,./-]+$/.test(value) ? value : `'${value.replace(/'/g, '\'\\\'\'')}'`;
}

/** `claude mcp add`, registering the endpoint with Claude Code. */
export function claudeCodeCommand(config: McpConfig): string {
  const lines = [`claude mcp add --transport http sql-explorer ${shellWord(config.url)}`];
  if (config.environment) {
    lines.push(`  --header ${shellWord(`${ENVIRONMENT_HEADER}: ${config.environment}`)}`);
  }
  return lines.join(' \\\n');
}

/** A project's `.mcp.json`, for Claude Code. */
export function mcpJson(config: McpConfig): string {
  const server: Record<string, unknown> = {type: 'http', url: config.url};
  if (config.environment) {
    server['headers'] = {[ENVIRONMENT_HEADER]: config.environment};
  }
  return JSON.stringify({mcpServers: {'sql-explorer': server}}, null, 2);
}

/**
 * `claude_desktop_config.json`. Claude Desktop's config launches local
 * commands, so the URL goes through `mcp-remote`, which bridges it; the
 * header is written without a space after the colon, as mcp-remote's own
 * documentation advises for argument lists.
 */
export function desktopJson(config: McpConfig): string {
  const args = ['-y', 'mcp-remote', config.url];
  if (config.environment) {
    args.push('--header', `${ENVIRONMENT_HEADER}:${config.environment}`);
  }
  return JSON.stringify({mcpServers: {'sql-explorer': {command: 'npx', args}}}, null, 2);
}

/** One tool as `tools/list` describes it. */
export interface McpToolInfo {
  name: string;
  description: string;
  /** False for the tag writes, true for everything that only reads. */
  readOnly: boolean;
}

interface ListedTool {
  name: string;
  description?: string;
  annotations?: {readOnlyHint?: boolean};
}

/** The tools from a `tools/list` response, in the order the server gives them. */
export function toolsOf(response: {result?: {tools?: ListedTool[]}}): McpToolInfo[] {
  return (response.result?.tools ?? []).map(tool => ({
    name: tool.name,
    description: tool.description ?? '',
    readOnly: tool.annotations?.readOnlyHint !== false
  }));
}

/** The JSON-RPC request that lists the tools; stateless, so no initialize first. */
export const TOOLS_LIST_REQUEST = {jsonrpc: '2.0', id: 1, method: 'tools/list', params: {}};
