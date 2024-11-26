# Redis cache client for Image Listings

This builds a simple image with some formatting tools to support the image caching feature in Skaha.  This image acts as a client
to a running Redis cache.  See the [cache-images.sh script](https://github.com/opencadc/science-platform/blob/main/deployment/helm/skaha/image-cache/cache-images.sh) in
Skaha, which is run from _within_ this Image.

See also the [`CronJob` and initialization `Job`](https://github.com/opencadc/science-platform/blob/main/deployment/helm/skaha/templates/image-caching-cronjob.yaml) on how this image is used from a Skaha deployment.
