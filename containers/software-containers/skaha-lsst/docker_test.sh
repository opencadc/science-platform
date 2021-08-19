cd
mkdir test
cd test
git lfs install
git clone https://github.com/lsst/testdata_ci_hsc.git
cd testdata_ci_hsc
setup -j -r .
cd ../
git clone https://github.com/lsst/ci_hsc_gen2.git
cd ci_hsc_gen2
setup -j -r .
./bin/linker.sh 
cd ../
mkdir DATA
echo "lsst.obs.hsc.HscMapper" > DATA/_mapper
ingestImages.py DATA $CI_HSC_GEN2_DIR/raw/*.fits --mode=link

