# Build, format, and lint targets

# Use local mill wrapper if available, otherwise system mill
MILL := $(shell if [ -x ./mill ]; then echo ./mill; else echo mill; fi)

.PHONY: build format fmt format-check lint fix clean

# Compile the project
build:
	$(MILL) ObjectStorage.compile

# Format code with Scalafmt
format:
	$(MILL) mill.scalalib.scalafmt.ScalafmtModule/reformatAll __.sources

# Alias for format
fmt: format

# Check formatting without making changes
format-check:
	$(MILL) mill.scalalib.scalafmt.ScalafmtModule/checkFormatAll __.sources

# Run Scalafix linter (check mode)
lint:
	$(MILL) ObjectStorage.fixCheck
	$(MILL) ObjectStorage.test.fixCheck

# Auto-fix linting issues
fix:
	$(MILL) ObjectStorage.fix
	$(MILL) ObjectStorage.test.fix

# Remove build artifacts
clean:
	rm -rf out/
