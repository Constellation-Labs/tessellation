const fetch = require( 'node-fetch' );

const sleep = ( ms ) => {
    return new Promise( ( resolve ) => setTimeout( resolve, ms ) );
};

const main = async () => {
    const urlParam = process.argv.find( ( arg ) => arg.includes( 'url' ) );
    const clusterNameParam = process.argv.find( ( arg ) =>
        arg.includes( 'cluster_name' )
    );

    if( !urlParam ) {
        throw Error( 'Url should be provided' );
    }

    const url = urlParam.split( '=' )[ 1 ];
    const clusterName = clusterNameParam.split( '=' )[ 1 ];

    console.log(`Starting to check if url: ${url} is started`)
    for( let idx = 0; idx < 11; idx++ ) {
        try {
            const response = await fetch( url, {
                method: 'GET',
                headers: {
                    Accept: 'application/json'
                }
            } );

            if( response.status === 200 ) {
                console.log( `${clusterName} started` );
                break;
            }

            if( idx === 10 ) {
                throw Error( `Error starting the ${clusterName}` );
            }
        } catch( e ) {
            console.log(
                `${clusterName} still booting... waiting 10s (${idx + 1} / 10)`
            );

            if( idx === 10 ) {
                throw Error( `Error starting the ${clusterName}` );
            }

            await sleep( 10 * 1000 );
        }
    }
};

main();
