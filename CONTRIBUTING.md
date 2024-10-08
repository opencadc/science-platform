# Contribution Guidelines

Thank you for considering contributing to this project! We welcome contributions from everyone. Please take a moment to review this document in order to make the contribution process easy and effective for everyone involved.

## Getting Started

1. **Fork the repository:** Create a fork of the repository to implement any changes.
2. **Clone the repository:** Clone the repository to your local machine using the following command:

   ```bash
   git clone https://github.com/<your-username>/<repository-name>.git
   ```

3. **Pre-Commit Setup:** We use pre-commit hooks to ensure code quality and consistency. Follow the instructions below to set up pre-commit hooks:

    ```bash
    pip3 install pre-commit
    pre-commit install --hook-type commit-msg
    ```

    >[!NOTE]
    > `--hook-type commit-msg` is required to ensure pre-commit hooks are run on commit messages. This will setup pre-commit hooks to run on every commit. If any of the hooks fail, the commit will be aborted and you will need to fix the issues before committing again.

    >[!IMPORTANT]
    >We follow the [Conventional Commits](https://www.conventionalcommits.org/en/v1.0.0/) specification for commit messages. Please ensure your commit messages follow this format. You can use various tools like [commitizen](https://github.com/commitizen-tools/commitizen) or VS Code extensions like [vscode-conventional-commits](https://marketplace.visualstudio.com/items?itemName=vivaxy.vscode-conventional-commits) to help you write commit messages in the correct format.

4. **Make Changes:** Implement the changes on your local machine.

5. **Push Changes:** Push the changes to your fork.

6. **Submit a Pull Request:** Submit a pull request to the main repository.

## Review Process

Your pull request will be reviewed by the maintainers. If any changes are required, you will receive feedback on the pull request. Once the changes are made, the pull request will be merged.
