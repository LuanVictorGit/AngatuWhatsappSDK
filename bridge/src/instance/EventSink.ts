/**
 * Decouples accounts from the transport that delivers their events.
 * `droppable` events (presence, delivery status) may be skipped when the
 * consumer is not keeping up; everything else is always delivered.
 */
export interface EventSink {
    emit(instanceId: string, event: string, data: unknown, droppable?: boolean): void;
}
