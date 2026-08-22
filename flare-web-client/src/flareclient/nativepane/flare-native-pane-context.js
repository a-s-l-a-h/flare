// "Channel B" for panes, mirroring flare-client-plugin-context.js — live
// getters only, never a value snapshotted at registration time.
export function createPaneContext({ getScreenName, getAuthToken, getBaseHttpUrl, notifyAuthFailure, setVariable, fireAction }) {
  return {
    getScreenName: () => getScreenName(),
    getAuthToken: () => getAuthToken(),
    getBaseHttpUrl: () => getBaseHttpUrl(),
    notifyAuthFailure: () => notifyAuthFailure(),
    setVariable: (name, value) => setVariable(name, value),
    fireAction: (actionName, payload) => fireAction(actionName, payload)
  };
}