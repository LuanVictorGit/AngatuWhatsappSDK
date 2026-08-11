/** Decouples instance/adapter code from the WebSocket transport that actually delivers events. */
export interface EventSink {
    emit(instanceId: string, event: string, data: unknown): void;
}
