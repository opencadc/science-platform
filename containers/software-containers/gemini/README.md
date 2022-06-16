# gemini_processing

## Gemini NIFS

To build the Nifty4Gemini (IRAF-based) processing container, cd to `nifs/dockerfiles/arcade-nifty/` and run `make` (make sure that docker is running). To use it to reduce a Gemini NIFS program and write output in your current working directory, run `docker run -v $(pwd):/scratch nat1405/nifty:0.1 runNifty nifsPipeline -s 'CADC' -f $PROGRAM_ID`. Some old documentation on running Nifty4Gemini is available [here](https://nifty4gemini.readthedocs.io/en/latest/). If you find the pipeline useful, please consider citing the Nifty4Gemini paper: https://ui.adsabs.harvard.edu/abs/2019AJ....158..153L/abstract.

