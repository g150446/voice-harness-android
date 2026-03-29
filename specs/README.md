# Harness Voice Technical Specifications

This directory contains detailed technical specifications for the Harness Voice project. Each specification provides in-depth documentation of a specific subsystem or component.

## Specification Files

### [architecture.md](./architecture.md)
**System-Level Architecture Documentation**

Covers the overall system design, module structure, and component interactions.

**Topics**:
- System architecture diagrams
- Module hierarchy (phone app and wear app)
- Component responsibilities
- Data flow diagrams
- State management across the system
- Threading model and concurrency
- Dependency management
- Security architecture
- Performance characteristics
- Build and deployment
- Future architecture considerations

**For**: Understanding the big picture, system design decisions, and component interactions.

---

### [gesture-detection.md](./gesture-detection.md)
**Gesture Recognition System Specification**

Detailed documentation of the threshold-based gesture detection algorithm.

**Topics**:
- Supported gestures (wrist flexion, external rotation)
- Detection algorithm and state machine
- Thresholds and parameters for each gesture type
- Baseline calibration system
- Gravity compensation
- Gesture type classification
- Conflict resolution
- Sensor configuration (50Hz sampling)
- Detection modes (control, test, data collection)
- Performance characteristics (SNR, latency, reliability)
- API interface (GestureDetector, GestureDetectionListener)
- Testing and validation procedures
- Threshold tuning methodology

**For**: Understanding or modifying gesture detection algorithms, tuning thresholds, adding new gestures.

---

### [phone-watch-communication.md](./phone-watch-communication.md)
**Communication Protocol Specification**

Defines the protocols for bidirectional communication between phone and watch.

**Topics**:
- Communication channels (Message API, Data API)
- Message protocol for watch app launch
- Data synchronization for API key sync
- Node discovery and connection management
- Watch launch behavior and display state management
- Power management (no wake locks on watch)
- Error handling and reliability
- Service lifecycle (WearableMessageListenerService)
- Data storage (SharedPreferences)
- Security considerations
- Performance characteristics (latency, battery impact)
- Testing and debugging procedures

**For**: Implementing new phone-watch communication features, debugging connection issues, understanding sync behavior.

---

### [groq-api-integration.md](./groq-api-integration.md)
**Groq API Integration Specification**

Complete documentation of the Groq API integration for transcription and chat.

**Topics**:
- API endpoints (Whisper transcription, Chat completions)
- Authentication (API key management)
- Audio transcription API (request/response format)
- Chat completions API (request/response format)
- Audio recording configuration (AAC, M4A, 44.1kHz)
- MediaRecorder setup and file management
- Network configuration (OkHttp client, timeouts)
- Error handling (network errors, API errors, recording errors)
- Workflow integration (complete recording-to-response flow)
- Status updates and UI feedback
- Performance characteristics (response times, file sizes)
- Security and privacy (encryption, data retention)
- Rate limits and quotas
- Testing procedures

**For**: Modifying API integration, adding new API features, debugging API issues, understanding the transcription flow.

---

### [sensor-data-format.md](./sensor-data-format.md)
**Sensor Data Format Specification**

Defines sensor data formats for logging, analysis, and gesture detection.

**Topics**:
- Sensor types (accelerometer, gyroscope)
- Real-time sensor event processing
- Data synchronization between sensors
- CSV logging format (columns, data types, units)
- File naming conventions and directory structure
- Data collection modes (flexion, negative samples)
- CSV writing implementation
- Internal data structures (SensorSample, BaselineValues)
- Logcat logging format
- Data analysis procedures
- Extracting data from watch (ADB commands)
- Analysis tools (Python, Excel)
- Threshold tuning methodology
- Storage management and data privacy

**For**: Collecting gesture data, analyzing sensor data, tuning gesture algorithms, understanding data formats.

---

## Quick Reference

### When to Use Each Spec

| Task | Relevant Specification |
|------|------------------------|
| Understanding overall system design | [architecture.md](./architecture.md) |
| Modifying gesture detection | [gesture-detection.md](./gesture-detection.md) |
| Adding new gestures | [gesture-detection.md](./gesture-detection.md) |
| Debugging phone-watch communication | [phone-watch-communication.md](./phone-watch-communication.md) |
| Configuring API key sync | [phone-watch-communication.md](./phone-watch-communication.md) |
| Modifying Groq API calls | [groq-api-integration.md](./groq-api-integration.md) |
| Changing audio recording settings | [groq-api-integration.md](./groq-api-integration.md) |
| Collecting sensor data | [sensor-data-format.md](./sensor-data-format.md) |
| Analyzing gesture samples | [sensor-data-format.md](./sensor-data-format.md) |
| Tuning detection thresholds | [gesture-detection.md](./gesture-detection.md) + [sensor-data-format.md](./sensor-data-format.md) |

### Cross-Cutting Concerns

Some features span multiple specifications:

**Voice Recording Feature**:
1. Gesture triggers recording → [gesture-detection.md](./gesture-detection.md)
2. Audio capture via MediaRecorder → [groq-api-integration.md](./groq-api-integration.md)
3. Upload to Groq API → [groq-api-integration.md](./groq-api-integration.md)
4. Overall flow → [architecture.md](./architecture.md)

**API Key Configuration**:
1. UI and sync logic → [phone-watch-communication.md](./phone-watch-communication.md)
2. Storage and usage → [groq-api-integration.md](./groq-api-integration.md)
3. Security → [architecture.md](./architecture.md)

**Gesture Data Collection**:
1. Detection algorithm → [gesture-detection.md](./gesture-detection.md)
2. Sensor event processing → [sensor-data-format.md](./sensor-data-format.md)
3. CSV logging → [sensor-data-format.md](./sensor-data-format.md)
4. Data analysis → [sensor-data-format.md](./sensor-data-format.md)

## Documentation Standards

### Specification Structure

Each specification follows a consistent structure:

1. **Overview**: High-level summary of the subsystem
2. **Technical Details**: In-depth technical documentation
3. **Implementation Details**: Code examples and configurations
4. **Testing**: Procedures for testing and validation
5. **Performance**: Characteristics and benchmarks
6. **Future Enhancements**: Planned improvements

### Code Examples

All code examples use:
- Kotlin syntax
- Actual class/method names from the codebase
- Inline comments for clarity
- Type annotations where helpful

### Diagrams

Text-based diagrams use:
- ASCII art for simple flows
- Tree structures for hierarchies
- Tables for structured data

## Version Control

**Current Version**: All specs reflect codebase as of 2025-01-30

**Updates**: Specifications should be updated when:
- Major architectural changes occur
- New features are added
- APIs are modified
- Thresholds or parameters change significantly

## Related Documentation

- [CLAUDE.md](../CLAUDE.md) - Quick reference for Claude Code
- [README.md](../README.md) - User-facing project documentation
- Source code comments - Implementation-level documentation

## Contributing

When updating specifications:

1. Keep technical accuracy as priority
2. Include code examples from actual implementation
3. Update cross-references if structure changes
4. Maintain consistent formatting
5. Add new sections to this README when adding new specs

## Questions?

For questions about:
- **Using the system**: See [README.md](../README.md)
- **Developing with Claude Code**: See [CLAUDE.md](../CLAUDE.md)
- **Technical details**: See relevant specification in this directory
