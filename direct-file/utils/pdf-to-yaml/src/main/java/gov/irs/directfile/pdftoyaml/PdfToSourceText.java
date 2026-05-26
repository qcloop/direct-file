package gov.irs.directfile.pdftoyaml;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * Extracts IRS source PDFs into one UTF-8 text file per PDF, with stable page markers.
 *
 * <p>This is intentionally separate from {@link PdfToYaml}; tax source publications need
 * citation text, while PDF form configuration needs field metadata.
 */
public class PdfToSourceText {
    private static final String PAGE_MARKER_PREFIX = "\n\n----- IRS_SOURCE_PAGE ";
    private static final String PAGE_MARKER_SUFFIX = " -----\n\n";

    private final Path pdfPath;
    private final Path outputDir;

    PdfToSourceText(final Path pdfPath, final Path outputDir) {
        this.pdfPath = pdfPath;
        this.outputDir = outputDir;
    }

    void writeOutput() throws IOException {
        if (!Files.exists(pdfPath)) {
            throw new IOException("Could not find PDF at path " + pdfPath);
        }
        Files.createDirectories(outputDir);

        final Path outputPath = outputDir.resolve(pdfPath.getFileName().toString().replaceAll("(?i)\\.pdf$", ".txt"));
        final StringBuilder output = new StringBuilder();

        try (PDDocument document = Loader.loadPDF(Files.readAllBytes(pdfPath))) {
            final int pages = document.getNumberOfPages();
            final PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            for (int page = 1; page <= pages; page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                output.append(PAGE_MARKER_PREFIX).append(page).append(PAGE_MARKER_SUFFIX);
                output.append(stripper.getText(document).strip()).append(System.lineSeparator());
            }
        }

        Files.writeString(outputPath, output.toString());
    }

    private static void processPath(final Path inputPath, final Path outputDir) throws IOException {
        if (Files.isDirectory(inputPath)) {
            try (Stream<Path> files = Files.list(inputPath)) {
                for (Path pdf : files.filter(path -> path.toString().toLowerCase().endsWith(".pdf")).sorted().toList()) {
                    new PdfToSourceText(pdf, outputDir).writeOutput();
                }
            }
        } else {
            new PdfToSourceText(inputPath, outputDir).writeOutput();
        }
    }

    private static void printUsageAndExit() {
        System.err.println("Usage:");
        System.err.println("Argument 1: path to a PDF file or directory of PDFs.");
        System.err.println("Argument 2: output directory for extracted .txt files.");
        System.exit(1);
    }

    public static void main(final String[] args) throws IOException {
        if (args.length != 2) {
            printUsageAndExit();
        }
        processPath(Path.of(args[0]), Path.of(args[1]));
    }
}
