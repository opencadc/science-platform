import glob, os
import numpy as np
import astropy.io.fits as fits

class ProductTagger:
	def __init__(self, configFile):
		self.scienceDirectories = self.parseConfig(configFile)


	def run(self):
		"""
		Tags products (and updates datalabels) in science directories with an extra extension that identifies calibrations.
		"""
		for scienceDir in self.scienceDirectories:	
			try:
				cal_ext = self.makeCalExt(scienceDir)
			except Exception:
				print("Failed to make calibration extension for {}. Skipping.".format(scienceDir))
				continue

			
			for productType in ["uncorrected", "telluric_corrected", "telluric_corrected_and_flux_cal"]:
				try:
					self.tagProducts(scienceDir, productType, cal_ext)
				except Exception:
					print("Failed to tag {} products in {}. Skipping.".format(productType, scienceDir))


	def makeCalExt(self, scienceDir):

		shift = glob.glob(os.path.join(scienceDir, "calibrations", "*_shift.fits"))
		flat = glob.glob(os.path.join(scienceDir, "calibrations", "*_flat.fits"))
		arc = glob.glob(os.path.join(scienceDir, "calibrations", "*_arc.fits"))
		ronchi = glob.glob(os.path.join(scienceDir, "calibrations", "*_ronchi.fits"))

		cals = [shift, flat, arc, ronchi]

		assert all([len(x) <= 1 for x in cals])

		cals = [os.path.split(x[0])[-1] if x else "" for x in cals]

		a1 = np.array(['Processed Shift File', 'Processed Flat', 'Processed Arc', 'Processed Ronchi'])
		a2 = np.array(cals)
		col1 = fits.Column(name='Calibration Type', format='50A', array=a1)
		col2 = fits.Column(name='Filename', format='50A', array=a2)

		cols = fits.ColDefs([col1, col2])

		hdu = fits.BinTableHDU.from_columns(cols, name="CAL")

		return hdu


	def tagProducts(self, scienceDir, productType, cal_ext):
		if productType == "uncorrected":
			prefix = "ctfbrsn"
			products = glob.glob(os.path.join(scienceDir, "products_"+productType, prefix+"N*"))
		elif productType == "telluric_corrected":
			prefix = "actfbrsn"
			products = glob.glob(os.path.join(scienceDir, "products_"+productType, prefix+"N*"))
		elif productType == "fluxcal_AND_telluric_corrected":
			prefix = "factfbrsn"
			products = glob.glob(os.path.join(scienceDir, "products_"+productType, prefix+"N*"))
		else:
			raise ValueError("Invalid product type: {}".format(productType))

		for product in products:
			try:
				with fits.open(product, mode='update') as hdu1:
					hdu1['PRIMARY'].header['DATALAB'] = hdu1['PRIMARY'].header['DATALAB']+'-'+prefix
					if not self.hasCalExt(hdu1):
						hdu1.append(cal_ext)
					hdu1.flush()
			except Exception:
				print("Problem adding cal extension to {}. Skipping.".format(product))
				continue


	def parseConfig(self, configFile):
		try:
			with open(configFile) as f:
				lines = f.readlines()
		except IOError:
			return []

		try:
			line = [line for line in lines if "scienceDirectoryList =" in line][0]
			line = line.rstrip('\n').replace("scienceDirectoryList = [", "")
			line = line.replace("]", "")
			line = line.replace("'", "")
			scienceDirectories = line.split(",")
		except Exception:
			return []

		return scienceDirectories

	def hasCalExt(self, hdulist):
		return any([x.name == "CAL" for x in hdulist])

			

foo = ProductTagger("./config.cfg")
foo.run()




