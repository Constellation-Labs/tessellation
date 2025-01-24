const { z } = require('zod');

const CliArgsSchema = z.object({
  dagL0PortPrefix: z.string().min(1, 'DAG L0 Port prefix cannot be empty'),
  dagL1PortPrefix: z.string().min(1, 'DAG L1 Port prefix cannot be empty'),
  metagraphL0PortPrefix: z.string().min(1, 'Metagraph L0 Port prefix cannot be empty'),
  currencyL1PortPrefix: z.string().min(1, 'Currency L1 Port prefix cannot be empty'),
  dataL1PortPrefix: z.string().min(1, 'Data L1 Port prefix cannot be empty'),
});

const parseSharedArgs = (args) => {
  const [dagL0PortPrefix, dagL1PortPrefix, metagraphL0PortPrefix, currencyL1PortPrefix, dataL1PortPrefix] = args;
  return CliArgsSchema.parse({ dagL0PortPrefix, dagL1PortPrefix, metagraphL0PortPrefix, currencyL1PortPrefix, dataL1PortPrefix });
};

module.exports = {
  parseSharedArgs,
}