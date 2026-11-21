# Stable releases only

Routine changes stay on the development branch. Review and group related fixes;
CI may assemble APKs to verify builds but does not distribute intermediate APKs.
Use the existing in-app diagnostics and benchmarks where possible. Record which
checks passed and which reader scenarios have not been verified on a device.
Do not ask readers to install intermediate builds or use ADB or a PC.

Publish only a reviewed, complete, signed stable release after relevant regression
and build checks pass and outstanding reliability risks have been assessed. Use
an incremented official version and version code, with no preview or test suffix.
Provide the direct signed APK link, not an archive or a CI artifact.
