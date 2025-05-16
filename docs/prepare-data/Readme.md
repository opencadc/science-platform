# Prepare Data

The Prepare Data feature enables automatic preparation of user datasets before session initialization in the Science Platform. This preemptive loading improves user experience by reducing wait times when working with large datasets.

## Configuration

The Prepare Data feature is controlled through a simple environment variable:

| Environment Variable | Description | Default |
|----------------------|-------------|---------|
| `PREPARE_DATA_ENABLED` | Enables or disables the data preparation functionality | `false` |

## How It Works

When enabled, the prepare-data feature:

1. Pre-fetches required datasets before a user session starts
2. Prepopulates user volume mounts
3. Ensures that the datasets are ready for immediate use when the session is launched
4. Crates a symlink to the dataset in the user's home directory

## Implementation

The feature is implemented in the `UserVolumeUtils` class, which is responsible for fetching the user volume mount paths and populate them in launch scripts.

## Workflow

1. User initiates a session in the Science Platform.
2. The system checks if the `PREPARE_DATA_ENABLED` environment variable is set to `true`.
3. If true, the system fetches the required datasets and prepopulates the user volume mounts.
4. These datasets are premounted and prepared before the session container starts.
5. The datasets are symlinked to the user's home directory for easy access.
