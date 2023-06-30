const { checkClusters } = require( '../shared/check_clusters' );

const main = async () => {
    await checkClusters(true)
};

main();
